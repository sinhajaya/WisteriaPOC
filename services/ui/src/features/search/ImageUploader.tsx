import { useCallback, useRef, useState } from 'react'

interface Props {
  disabled?: boolean
  onSubmit: (file: File, topK: number) => void
}

const ACCEPTED = ['image/jpeg', 'image/png', 'image/webp']

export default function ImageUploader({ disabled, onSubmit }: Props) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [topK, setTopK] = useState(8)
  const [dragging, setDragging] = useState(false)
  const [warning, setWarning] = useState<string | null>(null)

  const accept = useCallback((f: File | undefined) => {
    if (!f) return
    if (!ACCEPTED.includes(f.type)) {
      setWarning('Please choose a JPEG, PNG or WebP image.')
      return
    }
    setWarning(null)
    setFile(f)
    setPreview(URL.createObjectURL(f))
  }, [])

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault()
      setDragging(false)
      accept(e.dataTransfer.files?.[0])
    },
    [accept],
  )

  return (
    <div className="uploader">
      <div
        className={`dropzone${dragging ? ' dragging' : ''}${preview ? ' has-preview' : ''}`}
        onClick={() => inputRef.current?.click()}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        role="button"
        tabIndex={0}
      >
        {preview ? (
          <img className="preview" src={preview} alt="Your inspiration" />
        ) : (
          <div className="dropzone-empty">
            <div className="dropzone-icon">⬆</div>
            <p className="dropzone-title">Drop an inspiration image</p>
            <p className="dropzone-sub">or click to browse · JPEG, PNG, WebP</p>
          </div>
        )}
        <input
          ref={inputRef}
          type="file"
          accept={ACCEPTED.join(',')}
          hidden
          onChange={(e) => accept(e.target.files?.[0] ?? undefined)}
        />
      </div>

      {warning && <p className="field-warning">{warning}</p>}

      <div className="controls">
        <label className="topk">
          Results
          <select value={topK} onChange={(e) => setTopK(Number(e.target.value))} disabled={disabled}>
            {[4, 8, 12, 16, 20].map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
        <button
          className="primary"
          disabled={disabled || !file}
          onClick={() => file && onSubmit(file, topK)}
        >
          {disabled ? 'Matching…' : 'Find matches'}
        </button>
      </div>
    </div>
  )
}