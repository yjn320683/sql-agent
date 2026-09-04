import { describe, expect, it } from 'vitest';
import { createRealtimeTableDdl } from './realtimeTableDdl';

describe('createRealtimeTableDdl', () => {
  it('renders comments, keys, partitions and all options', () => {
    expect(createRealtimeTableDdl({
      id: 1,
      catalogName: 'paimon',
      databaseName: 'ods_real',
      tableName: 'orders',
      tableComment: "订单'明细",
      tableType: 'primary_key',
      creationSource: 'sync',
      physicalStatus: 'active',
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
});
