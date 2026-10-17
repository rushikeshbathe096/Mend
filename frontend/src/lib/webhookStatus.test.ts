import fs from 'fs';
import path from 'path';
import { WEBHOOK_EVENT_STATUSES } from './webhookStatus';

describe('WEBHOOK_EVENT_STATUSES', () => {
  it('matches the backend WebhookEventStatus enum exactly', () => {
    const enumSource = fs.readFileSync(
      path.join(__dirname, '../../../backend/src/main/java/com/mend/domain/enums/WebhookEventStatus.java'),
      'utf8',
    );
    const body = enumSource.slice(enumSource.indexOf('{') + 1, enumSource.lastIndexOf('}'));
    const backendValues = body.split(/[,;]/).map((s) => s.trim()).filter(Boolean);

    expect([...WEBHOOK_EVENT_STATUSES]).toEqual(backendValues);
  });

  it('does not offer statuses the backend never stores', () => {
    expect(WEBHOOK_EVENT_STATUSES).not.toContain('UNVERIFIED');
    expect(WEBHOOK_EVENT_STATUSES).not.toContain('DUPLICATE');
  });
});
