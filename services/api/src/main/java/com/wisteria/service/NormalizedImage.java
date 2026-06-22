package com.wisteria.service;

import java.awt.image.BufferedImage;

/**
 * Output of {@link ImageNormalizer}: a 336×336 JPEG (q≈85) ready for CLIP
 * and Claude, its Base64 encoding for the JSON payloads, and the decoded
 * BufferedImage used to compute the perceptual-hash cache key.
 */
public record NormalizedImage(byte[] jpegBytes, String base64, BufferedImage image) {}