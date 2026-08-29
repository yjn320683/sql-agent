import { describe, expect, it } from 'vitest';
import { queryFromParams, queryToParams } from './sessionQuery';

describe('sessionQuery', () => {
  it('parses supported filters and trims keywords', () => {
    const query = queryFromParams(new URLSearchParams(
      'status=archived&keyword=%20orders%20&page=3&pageSize=50&sortBy=createdAt&sortOrder=asc',
    ));

    expect(query).toEqual({
      status: 'archived',
      keyword: 'orders',
      page: 3,
      pageSize: 50,
      sortBy: 'createdAt',
      sortOrder: 'asc',
    });
  });

  it('falls back when URL parameters are invalid', () => {
    const query = queryFromParams(new URLSearchParams(
      'status=deleted&page=0&pageSize=999&sortBy=title&sortOrder=random',
    ));

    expect(query).toEqual({
      status: 'active',
      keyword: '',
      page: 1,
      pageSize: 20,
      sortBy: 'lastActiveAt',
      sortOrder: 'desc',
    });
  });

  it('omits an empty keyword when serializing', () => {
    const params = queryToParams({
      status: 'all',
      keyword: '',
      page: 2,
      pageSize: 20,
      sortBy: 'lastActiveAt',
      sortOrder: 'desc',
    });

    expect(params.has('keyword')).toBe(false);
    expect(params.get('status')).toBe('all');
    expect(params.get('page')).toBe('2');
  });
});
