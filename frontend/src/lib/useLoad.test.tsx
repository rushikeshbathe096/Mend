import { act, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { useLoad } from './useLoad';
import { errorMessage } from './errors';

type Deferred = { resolve: (value: string) => void; promise: Promise<string> };

function deferred(): Deferred {
  let resolve!: (value: string) => void;
  const promise = new Promise<string>((r) => {
    resolve = r;
  });
  return { resolve, promise };
}

function Probe({ requestKey, fetchFor }: { requestKey: string | null; fetchFor: (key: string) => Promise<string> }) {
  const [value, setValue] = useState('none');
  const { loading, reload } = useLoad(requestKey, async (isCurrent) => {
    const result = await fetchFor(requestKey as string);
    if (isCurrent()) setValue(result);
  });
  return (
    <div>
      <span data-testid="value">{value}</span>
      <span data-testid="loading">{String(loading)}</span>
      <button onClick={reload}>reload</button>
    </div>
  );
}

describe('useLoad', () => {
  it('is loading until the request for the current key has finished', async () => {
    const d = deferred();
    render(<Probe requestKey="a" fetchFor={() => d.promise} />);
    expect(screen.getByTestId('loading')).toHaveTextContent('true');

    await act(async () => d.resolve('A'));

    expect(screen.getByTestId('value')).toHaveTextContent('A');
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
  });

  it('a superseded request never overwrites the newer result', async () => {
    const requests: Record<string, Deferred> = { a: deferred(), b: deferred() };
    const fetchFor = (key: string) => requests[key].promise;
    const { rerender } = render(<Probe requestKey="a" fetchFor={fetchFor} />);
    rerender(<Probe requestKey="b" fetchFor={fetchFor} />);

    await act(async () => requests.b.resolve('B'));
    await act(async () => requests.a.resolve('A (stale)'));

    expect(screen.getByTestId('value')).toHaveTextContent('B');
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
  });

  it('reload() runs the load again for the same key and shows loading meanwhile', async () => {
    let calls = 0;
    const pending: Deferred[] = [];
    const fetchFor = () => {
      calls += 1;
      const d = deferred();
      pending.push(d);
      return d.promise;
    };
    render(<Probe requestKey="a" fetchFor={fetchFor} />);
    await act(async () => pending[0].resolve('first'));

    act(() => screen.getByText('reload').click());
    expect(screen.getByTestId('loading')).toHaveTextContent('true');
    await act(async () => pending[1].resolve('second'));

    expect(calls).toBe(2);
    expect(screen.getByTestId('value')).toHaveTextContent('second');
  });

  it('a null key loads nothing and is not loading', () => {
    const fetchFor = jest.fn();
    render(<Probe requestKey={null} fetchFor={fetchFor} />);
    expect(fetchFor).not.toHaveBeenCalled();
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
  });
});

describe('errorMessage', () => {
  it('uses the error message, else the fallback', () => {
    expect(errorMessage(new Error('boom'), 'fallback')).toBe('boom');
    expect(errorMessage({ message: 'from body' }, 'fallback')).toBe('from body');
    expect(errorMessage(new Error(''), 'fallback')).toBe('fallback');
    expect(errorMessage('a string', 'fallback')).toBe('fallback');
    expect(errorMessage(null, 'fallback')).toBe('fallback');
  });
});
