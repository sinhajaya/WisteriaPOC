package com.wisteria.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.wisteria.exception.WisteriaExceptions.ImageTooLargeException;
import com.wisteria.exception.WisteriaExceptions.UnsupportedImageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Input validation + normalisation for both the offline indexer and the
 * online query path (LLD v2.1 Day 3):
 *   [type gate] → 10 MB cap → EXIF orientation → resize shorter
 *   edge to 336 + centre-crop 336² → JPEG q≈85 → Base64.
 *
 * The type gate differs by caller: the catalog indexer ({@link #normalize})
 * enforces JPEG/PNG (S3 production constraint); the user-upload query path
 * ({@link #normalizeAnyType}) accepts any decodable format (JPEG/PNG/GIF/
 * BMP/TIFF + WebP via the imageio-webp plugin).
 *
 * NOTE: CLIP mean/std normalisation is NOT done here — the Python CLIP
 * server owns that. Java only resizes for payload size and fixes rotation.
 */
@Slf4j
@Component
public class ImageNormalizer {

    private static final long MAX_BYTES = 10L * 1024 * 1024;   // 10 MB
    private static final int TARGET = 336;
    private static final float JPEG_QUALITY = 0.85f;

    /**
     * Catalog / offline-indexer path. Production catalog images come from S3
     * and are JPEG/PNG only, so the magic-byte gate stays on here.
     */
    public NormalizedImage normalize(byte[] bytes) {
        return normalize(bytes, true);
    }

    /**
     * Online user-upload (query) path: accept ANY image type the platform can
     * decode — no JPEG/PNG restriction. Inspiration images arrive from phones
     * and browsers (WebP, etc.); only the 10 MB cap and genuinely undecodable
     * bytes are rejected, never the format itself.
     */
    public NormalizedImage normalizeAnyType(byte[] bytes) {
        return normalize(bytes, false);
    }

    private NormalizedImage normalize(byte[] bytes, boolean restrictToJpegPng) {
        if (bytes == null || bytes.length == 0) {
            throw new UnsupportedImageException("empty image");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ImageTooLargeException("image exceeds 10 MB cap (" + bytes.length + " bytes)");
        }
        if (restrictToJpegPng) {
            validateMagicBytes(bytes);
        }

        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new UnsupportedImageException("could not decode image: " + e.getMessage());
        }
        if (decoded == null) {
            throw new UnsupportedImageException("unsupported or undecodable image (no codec)");
        }

        BufferedImage oriented = applyExifOrientation(decoded, bytes);
        BufferedImage square = resizeAndCentreCrop(oriented, TARGET);
        byte[] jpeg = encodeJpeg(square, JPEG_QUALITY);
        String b64 = Base64.getEncoder().encodeToString(jpeg);
        return new NormalizedImage(jpeg, b64, square);
    }

    /** Reject anything that isn't a real JPEG/PNG, regardless of extension. */
    private static void validateMagicBytes(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return; // JPEG
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return; // PNG
        }
        throw new UnsupportedImageException("not a JPEG or PNG image");
    }

    /** Read EXIF orientation and rotate/flip so the image is upright. */
    private static BufferedImage applyExifOrientation(BufferedImage img, byte[] bytes) {
        int orientation = readOrientation(bytes);
        if (orientation <= 1) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        boolean swapDims = false;
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }                       // mirror H
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }                     // 180°
            case 4 -> { t.translate(0, h); t.scale(1, -1); }                        // mirror V
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); swapDims = true; }   // transpose
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); swapDims = true; } // 90° CW
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.rotate(Math.PI / 2); swapDims = true; } // transverse
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); swapDims = true; } // 90° CCW
            default -> { return img; }
        }
        BufferedImage dest = new BufferedImage(swapDims ? h : w, swapDims ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, t, null);
        g.dispose();
        return dest;
    }

    private static int readOrientation(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            log.debug("No readable EXIF orientation, assuming upright: {}", e.getMessage());
        }
        return 1;
    }

    /** Scale shorter edge to {@code target}, then centre-crop to target². */
    private static BufferedImage resizeAndCentreCrop(BufferedImage src, int target) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = (double) target / Math.min(w, h);
        int sw = Math.max(target, (int) Math.round(w * scale));
        int sh = Math.max(target, (int) Math.round(h * scale));

        BufferedImage scaled = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, sw, sh, null);
        g.dispose();

        int x = (sw - target) / 2;
        int y = (sh - target) / 2;
        BufferedImage cropped = new BufferedImage(target, target, BufferedImage.TYPE_INT_RGB);
        Graphics2D gc = cropped.createGraphics();
        gc.drawImage(scaled, -x, -y, null);
        gc.dispose();
        return cropped;
    }

    private static byte[] encodeJpeg(BufferedImage img, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } catch (Exception e) {
            throw new UnsupportedImageException("JPEG encode failed: " + e.getMessage());
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}