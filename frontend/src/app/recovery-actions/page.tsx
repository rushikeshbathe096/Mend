'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

export default function RecoveryActionsRedirect() {
  const router = useRouter();

  useEffect(() => {
    router.replace('/actions');
  }, [router]);

  return null;
}
