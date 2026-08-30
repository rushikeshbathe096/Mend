import Link from 'next/link';

export default function Login() {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gray-50 text-gray-900 p-8">
      <main className="w-full max-w-md bg-white p-8 rounded-xl shadow-sm border border-gray-100 space-y-6 text-center">
        <h1 className="text-3xl font-bold tracking-tight text-gray-900">Login</h1>
        <p className="text-gray-500">Sign in to your Mend dashboard</p>
        
        <div className="space-y-4 pt-4">
          <p className="text-sm text-gray-400 italic">Authentication is not implemented in Phase 1.</p>
        </div>

        <div className="pt-6">
          <Link 
            href="/" 
            className="text-blue-600 hover:underline text-sm font-medium"
          >
            &larr; Back to Home
          </Link>
        </div>
      </main>
    </div>
  );
}
