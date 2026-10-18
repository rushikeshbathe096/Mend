import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { CustomerRepliesPanel } from './CustomerRepliesPanel';
import { api } from '@/lib/api';
import { CustomerReplyDto } from '@/types';

jest.mock('@/lib/api');

const parsed: CustomerReplyDto = {
  id: 'r-1',
  campaignId: 'cmp-1',
  text: 'Please stop messaging me',
  status: 'PARSED',
  attempts: 1,
  lastError: null,
  createdAt: '2026-10-18T10:00:00Z',
  suggestion: {
    valid: true,
    intent: 'OPT_OUT',
    promisedDate: null,
    abstain: false,
    validationError: null,
    modelId: null,
    promptVersion: 'reply-parse-v1',
    effect: 'CONTACT_OPTED_OUT',
    createdAt: '2026-10-18T10:00:05Z',
  },
};

describe('CustomerRepliesPanel', () => {
  beforeEach(() => jest.clearAllMocks());

  it('labels the model output as a suggestion, shows the policy effect, and never invents a model id', async () => {
    (api.get as jest.Mock).mockResolvedValue([parsed]);
    render(<CustomerRepliesPanel campaignId="cmp-1" merchantId="m-1" canClearOptOut={false} />);

    expect(await screen.findByText('Please stop messaging me')).toBeInTheDocument();
    expect(screen.getByText('Model suggestion (not a decision)')).toBeInTheDocument();
    expect(screen.getByText('OPT_OUT')).toBeInTheDocument();
    expect(screen.getByText(/Contact stopped/)).toBeInTheDocument();
    expect(screen.getByText('not reported')).toBeInTheDocument();
    expect(screen.queryByText(/confidence/i)).not.toBeInTheDocument();
    expect(api.get).toHaveBeenCalledWith('/campaigns/cmp-1/replies', { merchantId: 'm-1' });
  });

  it('shows invalid output as ignored and offers a retry only for FAILED replies', async () => {
    (api.get as jest.Mock).mockResolvedValue([
      { ...parsed, id: 'r-2', suggestion: { ...parsed.suggestion!, valid: false, intent: null, validationError: 'INTENT_NOT_IN_ENUM', effect: 'NONE' } },
      { ...parsed, id: 'r-3', text: 'second', status: 'FAILED', lastError: 'MODEL_UNAVAILABLE', suggestion: null },
    ]);
    (api.post as jest.Mock).mockResolvedValue({});
    render(<CustomerRepliesPanel campaignId="cmp-1" merchantId="m-1" canClearOptOut={false} />);

    expect(await screen.findByText(/Invalid model output/)).toBeInTheDocument();
    expect(screen.getByText('MODEL_UNAVAILABLE')).toBeInTheDocument();
    const retries = screen.getAllByRole('button', { name: 'Retry parsing' });
    expect(retries).toHaveLength(1);
    fireEvent.click(retries[0]);
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/campaigns/cmp-1/replies/r-3/retry', undefined, { merchantId: 'm-1' }));
  });

  it('records a reply, and lets only a merchant admin clear an opt-out', async () => {
    (api.get as jest.Mock).mockResolvedValue([]);
    (api.post as jest.Mock).mockResolvedValue({});
    (api.delete as jest.Mock).mockResolvedValue({ cleared: true });
    const onCleared = jest.fn();
    const { rerender } = render(
      <CustomerRepliesPanel campaignId="cmp-1" merchantId="m-1" optedOutAt="2026-10-18T10:00:00Z" canClearOptOut={false} />,
    );
    expect(await screen.findByText(/Customer opted out of contact/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear opt-out' })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/Record a customer reply/i), { target: { value: 'I paid yesterday' } });
    fireEvent.click(screen.getByRole('button', { name: 'Record reply' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/campaigns/cmp-1/replies', { text: 'I paid yesterday' }, { merchantId: 'm-1' }));

    rerender(<CustomerRepliesPanel campaignId="cmp-1" merchantId="m-1" optedOutAt="2026-10-18T10:00:00Z" canClearOptOut onOptOutCleared={onCleared} />);
    fireEvent.click(screen.getByRole('button', { name: 'Clear opt-out' }));
    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('/campaigns/cmp-1/opt-out', { merchantId: 'm-1' }));
    await waitFor(() => expect(onCleared).toHaveBeenCalled());
  });
});
