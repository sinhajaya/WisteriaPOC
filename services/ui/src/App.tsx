import AttributePills from './components/AttributePills'
import LoadingState from './components/LoadingState'
import ImageUploader from './features/search/ImageUploader'
import ResultsGrid from './features/search/ResultsGrid'
import { useStyleQuery } from './features/search/useStyleQuery'

export default function App() {
  const { loading, error, result, run } = useStyleQuery()

  return (
    <div className="app">
      <header className="masthead">
        <h1>Wisteria</h1>
        <p>Upload a photo of a style you love. Get the products that match it.</p>
      </header>

      <main className="container">
        <ImageUploader disabled={loading} onSubmit={(file, topK) => run(file, { topK })} />

        {error && <div className="error-banner">{error}</div>}

        {loading && <LoadingState />}

        {result && !loading && (
          <>
            <AttributePills attributes={result.query_attributes} />
            <ResultsGrid response={result} />
          </>
        )}
      </main>

      <footer className="footer">Wisteria Visual Style Matching · local POC</footer>
    </div>
  )
}