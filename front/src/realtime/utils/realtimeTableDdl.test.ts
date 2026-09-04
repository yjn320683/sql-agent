import { describe, expect, it } from 'vitest';
import { createRealtimeTableDdl, parseRealtimeTableDdl, toRealtimeTableDraft } from './realtimeTableDdl';

describe('createRealtimeTableDdl', () => {
  it('renders comments, keys, partitions and all options', () => {
    expect(createRealtimeTableDdl({
      catalogName: 'paimon',
      databaseName: 'ods_real',
      tableName: 'orders',
      tableComment: "订单'明细",
      tableType: 'primary_key',
      columns: [
        { name: 'id', dataType: 'BIGINT', nullable: false, primaryKey: true, comment: '订单ID' },
        { name: 'dt', dataType: 'STRING', nullable: false, primaryKey: true, partitionKey: true },
      ],
      options: { bucket: '2', 'changelog-producer': 'input' },
    })).toBe("CREATE TABLE `paimon`.`ods_real`.`orders` (\n"
      + "  `id` BIGINT NOT NULL COMMENT '订单ID',\n"
      + "  `dt` STRING NOT NULL,\n"
      + "  PRIMARY KEY (`id`, `dt`) NOT ENFORCED\n"
      + ")\nCOMMENT '订单''明细'\nPARTITIONED BY (`dt`)\n"
      + "WITH (\n  'bucket' = '2',\n  'changelog-producer' = 'input'\n);");
  });

  it('parses a Paimon DDL and can render it back from the visual form', () => {
    const parsed = parseRealtimeTableDdl("CREATE TABLE IF NOT EXISTS `paimon`.`ods_real`.`orders` (\n"
      + "  `id` BIGINT NOT NULL COMMENT '订单ID',\n"
      + "  `amount` DECIMAL(18, 2),\n"
      + "  `dt` STRING NOT NULL,\n"
      + "  PRIMARY KEY (`id`, `dt`) NOT ENFORCED\n"
      + ") COMMENT '订单明细' PARTITIONED BY (`dt`) WITH (\n"
      + "  'bucket' = '4',\n  'changelog-producer' = 'input',\n"
      + "  'sink.parallelism' = '2',\n  'consumer.expiration-time' = '1 d',\n"
      + "  'snapshot.time-retained' = '2 d'\n);");

    expect(parsed).toMatchObject({
      catalogName: 'paimon', databaseName: 'ods_real', tableName: 'orders', tableComment: '订单明细',
      tableType: 'primary_key', bucket: 4, changelogProducer: 'input', sinkParallelism: 2,
      consumerExpiration: '1 d', extraOptions: [{ key: 'snapshot.time-retained', value: '2 d' }],
    });
    expect(parsed.columns).toEqual([
      { name: 'id', dataType: 'BIGINT', nullable: false, primaryKey: true, partitionKey: false, comment: '订单ID' },
      { name: 'amount', dataType: 'DECIMAL(18, 2)', nullable: true, primaryKey: false, partitionKey: false, comment: '' },
      { name: 'dt', dataType: 'STRING', nullable: false, primaryKey: true, partitionKey: true, comment: '' },
    ]);
    expect(createRealtimeTableDdl(toRealtimeTableDraft(parsed))).toContain("'consumer.expiration-time' = '1 d'");
  });

  it('rejects unsupported table properties instead of silently dropping them', () => {
    expect(() => parseRealtimeTableDdl("CREATE TABLE ods_real.orders (`id` BIGINT) WITH ('path' = 'file:///tmp');"))
      .toThrow('暂不支持的表属性：path');
  });
});
