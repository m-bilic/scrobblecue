import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { App } from './App'
import { resumeLive } from './lib/live'
import { flushQueue } from './lib/scrobbler'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
})

// Send anything left in the local queue: on start and whenever the connection comes back
void flushQueue()
window.addEventListener('online', () => void flushQueue())
// A side that was playing live when the page was closed continues (or shows "flip?")
void resumeLive()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
