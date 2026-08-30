import Link from 'next/link';

export default function Home() {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gray-50 text-gray-900 p-8">
      <main className="max-w-2xl text-center space-y-6">
        <h1 className="text-5xl font-bold tracking-tight text-blue-600">Mend</h1>
        <p className="text-xl text-gray-600 font-medium">AI-powered payment recovery platform</p>
        <div className="pt-8">
          <Link 
            href="/login" 
            className="px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
          >
            Go to Login
          </Link>
        </div>
      </main>
      <footer className="absolute bottom-4 text-sm text-gray-400">
        Status: Systems Operational
      </footer>
    </div>
  );
}
