import { useEffect, useEffectEvent, useState } from 'react';

/**
 * Runs `load` when `requestKey` changes (merchant, page, filters...) and when `reload()` is called.
 *
 * `loading` is derived from which request has finished, so nothing is set synchronously inside an effect
 * (react-hooks/set-state-in-effect). Contract for `load`: set state only after an `await`, and only while `isCurrent()`
 * is true (a newer request or an unmount makes the old one stale). A `null` key means "nothing to load" (e.g. no merchant
 * selected yet): `loading` is false and `load` is not called.
 */
export function useLoad(
  requestKey: string | null,
  load: (isCurrent: () => boolean) => Promise<void>,
): { loading: boolean; reload: () => void } {
  const [reloads, setReloads] = useState(0);
  const [finishedKey, setFinishedKey] = useState<string | null>(null);
  const key = requestKey === null ? null : `${requestKey}#${reloads}`;
  const runLoad = useEffectEvent((isCurrent: () => boolean) => load(isCurrent));

  useEffect(() => {
    if (key === null) return;
    let current = true;
    const isCurrent = () => current;
    runLoad(isCurrent)
      .catch(() => {
        // load handles its own errors; a rejection here must not become an unhandled promise
      })
      .finally(() => {
        if (current) setFinishedKey(key);
      });
    return () => {
      current = false;
    };
  }, [key]);

  return {
    loading: key !== null && finishedKey !== key,
    reload: () => setReloads((n) => n + 1),
  };
}
