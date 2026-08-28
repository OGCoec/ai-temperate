import fs from "node:fs/promises";
import path from "node:path";
import { pathToFileURL } from "node:url";
import {
  FileBlob,
  SpreadsheetFile,
} from "@oai/artifact-tool";

const HYBRID_ID_PATTERN = /^[A-Za-z0-9_-]{22}$/;
const LONG_ID_PATTERN = /^[A-Za-z0-9_-]{11}$/;
const POSITIVE_DECIMAL_PATTERN = /^[1-9][0-9]*$/;
const HEX_16_PATTERN = /^[0-9a-fA-F]{32}$/;
const JAVA_LONG_MAX = 9223372036854775807n;
const WRITE_CHUNK_SIZE = 2500;

const GROUP_CODES = [
  "E-P1",
  "E-PR",
  "E-A1",
  "E-AR",
  "H-P1",
  "H-PR",
  "H-A1",
  "H-AR",
];

const SHEET_NAMES = [
  "测试总览",
  "ID映射",
  "membership_order",
  "membership_payment_callback",
  "user_membership_quota",
  "区段证据",
  "一致性校验",
  "字段说明",
];

const TIER_NAMES = new Map([
  ["0", "FREE"],
  ["1", "GO"],
  ["2", "EDU"],
  ["3", "TEAM"],
  ["4", "PLUS"],
  ["5", "PRO"],
  ["6", "MAX"],
]);

const ORDER_STATUS_NAMES = new Map([
  ["0", "PENDING_PAYMENT"],
  ["1", "CLOSING"],
  ["2", "PAID"],
  ["3", "CANCELLED"],
  ["4", "CLOSED"],
]);

function toUnpaddedBase64Url(buffer) {
  return buffer
    .toString("base64")
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/u, "");
}

export function encodeHybridHexToBase64Url(rawHex) {
  const normalized = String(rawHex ?? "").trim();
  if (!HEX_16_PATTERN.test(normalized)) {
    throw new Error("Hybrid ID must be exactly 16 bytes encoded as 32 hexadecimal characters.");
  }
  const bytes = Buffer.from(normalized, "hex");
  const encoded = toUnpaddedBase64Url(bytes);
  if (!HYBRID_ID_PATTERN.test(encoded)) {
    throw new Error("Hybrid ID did not produce canonical 22-character Base64URL.");
  }
  const decoded = Buffer.from(encoded, "base64url");
  if (!decoded.equals(bytes) || toUnpaddedBase64Url(decoded) !== encoded) {
    throw new Error("Hybrid ID failed canonical Base64URL round-trip validation.");
  }
  return encoded;
}

export function encodePositiveLongToBase64Url(rawDecimal) {
  const normalized = String(rawDecimal ?? "").trim();
  if (!POSITIVE_DECIMAL_PATTERN.test(normalized)) {
    throw new Error("Long public ID input must be a positive decimal integer.");
  }
  const value = BigInt(normalized);
  if (value <= 0n || value > JAVA_LONG_MAX) {
    throw new Error("Long public ID input must be within Java positive Long range.");
  }
  const bytes = Buffer.alloc(8);
  bytes.writeBigInt64BE(value);
  const encoded = toUnpaddedBase64Url(bytes);
  if (!LONG_ID_PATTERN.test(encoded)) {
    throw new Error("Long ID did not produce canonical 11-character Base64URL.");
  }
  const decoded = Buffer.from(encoded, "base64url");
  if (
    decoded.length !== 8 ||
    decoded.readBigInt64BE() !== value ||
    toUnpaddedBase64Url(decoded) !== encoded
  ) {
    throw new Error("Long ID failed canonical Base64URL round-trip validation.");
  }
  return encoded;
}

function parseArguments(argv) {
  const result = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || value === undefined) {
      throw new Error(`Invalid command argument at position ${index}.`);
    }
    result.set(key.slice(2), value);
  }
  for (const required of [
    "input-workbook",
    "output-workbook",
    "metadata",
    "data-directory",
    "preview-directory",
    "verification-file",
  ]) {
    if (!result.has(required)) {
      throw new Error(`Missing required argument --${required}.`);
    }
  }
  return Object.fromEntries(result);
}

function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (quoted) {
      if (character === '"') {
        if (text[index + 1] === '"') {
          field += '"';
          index += 1;
        } else {
          quoted = false;
        }
      } else {
        field += character;
      }
      continue;
    }
    if (character === '"') {
      quoted = true;
    } else if (character === ",") {
      row.push(field);
      field = "";
    } else if (character === "\n") {
      row.push(field.endsWith("\r") ? field.slice(0, -1) : field);
      rows.push(row);
      row = [];
      field = "";
    } else {
      field += character;
    }
  }
  if (quoted) {
    throw new Error("CSV input ended inside a quoted field.");
  }
  if (field.length > 0 || row.length > 0) {
    row.push(field.endsWith("\r") ? field.slice(0, -1) : field);
    rows.push(row);
  }
  return rows;
}

async function readCsvObjects(filePath) {
  const text = await fs.readFile(filePath, "utf8");
  const rows = parseCsv(text.replace(/^\uFEFF/u, ""));
  if (rows.length === 0) {
    throw new Error(`CSV is empty: ${filePath}`);
  }
  const headers = rows[0];
  if (new Set(headers).size !== headers.length || headers.some((header) => !header)) {
    throw new Error(`CSV contains empty or duplicate headers: ${filePath}`);
  }
  return rows.slice(1).filter((cells) => cells.some((cell) => cell !== "")).map((cells, rowIndex) => {
    if (cells.length !== headers.length) {
      throw new Error(`CSV row ${rowIndex + 2} has an unexpected column count: ${filePath}`);
    }
    return Object.fromEntries(headers.map((header, columnIndex) => [header, cells[columnIndex]]));
  });
}

function requireValue(record, field, context) {
  const value = String(record[field] ?? "");
  if (!value) {
    throw new Error(`${context} is missing required field ${field}.`);
  }
  return value;
}

function verifyHybridPair(rawHex, databaseEncoded, context) {
  const encoded = encodeHybridHexToBase64Url(rawHex);
  if (encoded !== databaseEncoded || !HYBRID_ID_PATTERN.test(databaseEncoded)) {
    throw new Error(`${context} does not match PostgreSQL hybrid_id_to_base64url output.`);
  }
  return encoded;
}

function requireExactRowCount(rows, expected, name) {
  if (rows.length !== expected) {
    throw new Error(`${name} must contain exactly ${expected} data rows; received ${rows.length}.`);
  }
}

async function collectGarbage(stage) {
  if (typeof global.gc !== "function") {
    throw new Error("Workbook builder requires Node.js --expose-gc without changing the heap limit.");
  }
  await new Promise((resolve) => setImmediate(resolve));
  global.gc();
  await new Promise((resolve) => setImmediate(resolve));
  const memory = process.memoryUsage();
  process.stdout.write(
    `[${new Date().toISOString()}] ${stage}; heapUsed=${Math.round(memory.heapUsed / 1048576)}MB; rss=${Math.round(memory.rss / 1048576)}MB\n`,
  );
}

function numberFromDecimal(value, field) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${field} is not a finite decimal value.`);
  }
  return parsed;
}

function columnName(index) {
  let value = index + 1;
  let name = "";
  while (value > 0) {
    const remainder = (value - 1) % 26;
    name = String.fromCharCode(65 + remainder) + name;
    value = Math.floor((value - 1) / 26);
  }
  return name;
}

function setColumnWidth(sheet, columnIndex, rowCount, width) {
  sheet.getRangeByIndexes(0, columnIndex, Math.max(rowCount, 1), 1).format.columnWidth = width;
}

function hideColumn(sheet, columnIndex, rowCount) {
  sheet.getRangeByIndexes(0, columnIndex, Math.max(rowCount, 1), 1).format.columnHidden = true;
}

function writeRowsInChunks(sheet, startRow, values, columnCount) {
  for (let offset = 0; offset < values.length; offset += WRITE_CHUNK_SIZE) {
    const chunk = values.slice(offset, offset + WRITE_CHUNK_SIZE);
    sheet.getRangeByIndexes(startRow + offset, 0, chunk.length, columnCount).values = chunk;
  }
}

function writeRecordsInChunks(sheet, startRow, rows, columns) {
  for (let offset = 0; offset < rows.length; offset += WRITE_CHUNK_SIZE) {
    const end = Math.min(offset + WRITE_CHUNK_SIZE, rows.length);
    const values = [];
    for (let rowIndex = offset; rowIndex < end; rowIndex += 1) {
      const record = rows[rowIndex];
      values.push(columns.map((column) => {
        const value = column.value(record, rowIndex);
        return value === undefined ? null : value;
      }));
    }
    sheet.getRangeByIndexes(startRow + offset, 0, values.length, columns.length).values = values;
  }
}

function styleTabularSheet(sheet, columns, rowCount, tableName, hiddenColumns = []) {
  const lastColumn = columnName(columns.length - 1);
  const lastRow = rowCount + 1;
  sheet.showGridLines = false;
  sheet.freezePanes.freezeRows(1);
  sheet.freezePanes.freezeColumns(Math.min(2, columns.length));
  const header = sheet.getRange(`A1:${lastColumn}1`);
  header.format = {
    fill: "#155E75",
    font: { bold: true, color: "#FFFFFF" },
    horizontalAlignment: "center",
    verticalAlignment: "center",
    wrapText: true,
    borders: { preset: "outside", style: "thin", color: "#0E7490" },
  };
  header.format.rowHeight = 32;
  const table = sheet.tables.add(`A1:${lastColumn}${lastRow}`, true, tableName);
  table.style = "TableStyleMedium2";
  table.showFilterButton = true;
  for (let index = 0; index < columns.length; index += 1) {
    const width = columns[index].width ?? 16;
    setColumnWidth(sheet, index, lastRow, width);
  }
  for (const columnIndex of hiddenColumns) {
    hideColumn(sheet, columnIndex, lastRow);
  }
}

function createDataSheet(workbook, name, columns, rows, tableName, hiddenColumns = []) {
  const sheet = workbook.worksheets.add(name);
  sheet.getRangeByIndexes(0, 0, 1, columns.length).values = [columns.map((column) => column.header)];
  writeRecordsInChunks(sheet, 1, rows, columns);
  styleTabularSheet(sheet, columns, rows.length, tableName, hiddenColumns);
  for (let index = 0; index < columns.length; index += 1) {
    if (columns[index].format) {
      sheet.getRangeByIndexes(1, index, rows.length, 1).format.numberFormat = columns[index].format;
    }
  }
  return sheet;
}

function transformOrders(rows) {
  rows.forEach((row, index) => {
    const orderId = verifyHybridPair(
      requireValue(row, "order_id_raw_hex", `membership_order row ${index + 1}`),
      requireValue(row, "order_id_base64url", `membership_order row ${index + 1}`),
      `membership_order row ${index + 1}`,
    );
    const loginIdentityRaw = requireValue(
      row,
      "login_identity_id_raw",
      `membership_order row ${index + 1}`,
    );
    row.order_id_base64url = orderId;
    row.login_identity_public_id = encodePositiveLongToBase64Url(loginIdentityRaw);
    row.status_name = ORDER_STATUS_NAMES.get(row.status_code) ?? "UNKNOWN";
    row.membership_tier_name = TIER_NAMES.get(row.membership_tier_code) ?? "UNKNOWN";
  });
  return rows;
}

function transformCallbacks(rows) {
  rows.forEach((row, index) => {
    row.callback_id_base64url = verifyHybridPair(
      requireValue(row, "callback_id_raw_hex", `membership_payment_callback row ${index + 1}`),
      requireValue(row, "callback_id_base64url", `membership_payment_callback row ${index + 1}`),
      `membership_payment_callback callback ID row ${index + 1}`,
    );
    row.order_id_base64url = verifyHybridPair(
      requireValue(row, "order_id_raw_hex", `membership_payment_callback row ${index + 1}`),
      requireValue(row, "order_id_base64url", `membership_payment_callback row ${index + 1}`),
      `membership_payment_callback order ID row ${index + 1}`,
    );
  });
  return rows;
}

function transformQuotas(rows) {
  rows.forEach((row, index) => {
    const quotaIdRaw = requireValue(row, "quota_id_raw", `user_membership_quota row ${index + 1}`);
    const loginIdentityRaw = requireValue(
      row,
      "login_identity_id_raw",
      `user_membership_quota row ${index + 1}`,
    );
    row.quota_id_base64url = encodePositiveLongToBase64Url(quotaIdRaw);
    row.login_identity_public_id = encodePositiveLongToBase64Url(loginIdentityRaw);
    row.membership_tier_name = TIER_NAMES.get(row.membership_tier_code) ?? "UNKNOWN";
  });
  return rows;
}

function transformIdMapping(rows) {
  rows.forEach((row, index) => {
    const orderId = verifyHybridPair(
      requireValue(row, "membership_order_id_raw_hex", `ID mapping row ${index + 1}`),
      requireValue(row, "membership_order_id_base64url", `ID mapping row ${index + 1}`),
      `ID mapping order row ${index + 1}`,
    );
    const callbackId = verifyHybridPair(
      requireValue(row, "membership_payment_callback_id_raw_hex", `ID mapping row ${index + 1}`),
      requireValue(row, "membership_payment_callback_id_base64url", `ID mapping row ${index + 1}`),
      `ID mapping callback row ${index + 1}`,
    );
    const quotaRaw = requireValue(row, "user_membership_quota_id_raw", `ID mapping row ${index + 1}`);
    const loginRaw = requireValue(row, "login_identity_id_raw", `ID mapping row ${index + 1}`);
    row.membership_order_id_base64url = orderId;
    row.membership_payment_callback_id_base64url = callbackId;
    row.user_membership_quota_id_base64url = encodePositiveLongToBase64Url(quotaRaw);
    row.login_identity_public_id = encodePositiveLongToBase64Url(loginRaw);
  });
  return rows;
}

function createSummarySheet(workbook, metadata, existingSheet = null) {
  const sheet = existingSheet ?? workbook.worksheets.add("测试总览");
  sheet.showGridLines = false;
  sheet.mergeCells("A1:H1");
  sheet.getRange("A1").values = [[
    `${metadata.expectedRowsPerGroup / 1000}K×${metadata.expectedGroupCodes.length} 会员测试数据归档`,
  ]];
  sheet.getRange("A1:H1").format = {
    fill: "#083344",
    font: { bold: true, color: "#FFFFFF" },
    horizontalAlignment: "left",
    verticalAlignment: "center",
  };
  sheet.getRange("A1:H1").format.rowHeight = 38;
  const labelsAndValues = [
    ["项目", "值"],
    ["测试规模", `${metadata.runScale} / ${metadata.expectedRowsPerGroup / 1000}K×${metadata.expectedGroupCodes.length}`],
    ["主运行 RunId", metadata.masterRunId],
    ["总范围", `${metadata.expectedTotalRows.toLocaleString("en-US")} 个用户 / ${metadata.expectedTotalRows.toLocaleString("en-US")} 个订单`],
    ["区段结果", null],
    ["Suite 状态", metadata.suiteStatus],
    ["当前阶段", metadata.suitePhase],
    ["失败阶段", metadata.suiteFailureStage || ""],
    ["失败原因", metadata.suiteFailureMessage || ""],
    ["数据快照状态", "已归档"],
    ["完全测试完成", null],
    ["生成时间（UTC）", metadata.generatedAtUtc],
  ];
  sheet.getRange("A3:B14").values = labelsAndValues;
  sheet.getRange("A3:B3").format = {
    fill: "#155E75",
    font: { bold: true, color: "#FFFFFF" },
  };
  sheet.getRange("B8").format = metadata.suiteStatus === "PASS"
    ? { fill: "#DCFCE7", font: { bold: true, color: "#166534" } }
    : { fill: "#FEE2E2", font: { bold: true, color: "#991B1B" } };
  sheet.getRange("B7").conditionalFormats.add(
    "containsText",
    { text: "PASS", format: { fill: "#DCFCE7", font: { bold: true, color: "#166534" } } },
  );
  sheet.getRange("B13").format = metadata.suiteStatus === "PASS"
    ? { fill: "#DCFCE7", font: { bold: true, color: "#166534" } }
    : { fill: "#FEE2E2", font: { bold: true, color: "#991B1B" } };
  sheet.mergeCells("A17:H18");
  sheet.getRange("A17").values = [[metadata.suiteStatus === "PASS"
    ? "注意：区段数据通过不等于整套 Suite 通过；只有区段、数据一致性和 Suite 总门禁全部 PASS，才能标记完全测试完成。"
    : "注意：八个区段的数据与功能证据通过，不等于整套 Suite 通过。本工作簿是失败运行的数据快照，不得作为“完全测试完成”的证明。"]];
  sheet.getRange("A17:H18").format = {
    fill: "#FEF3C7",
    font: { bold: true, color: "#92400E" },
    wrapText: true,
    verticalAlignment: "center",
    borders: { preset: "outside", style: "thin", color: "#F59E0B" },
  };
  setColumnWidth(sheet, 0, 18, 24);
  setColumnWidth(sheet, 1, 18, 70);
  for (let column = 2; column < 8; column += 1) {
    setColumnWidth(sheet, column, 18, 12);
  }
  sheet.getRange("A3:A14").format.font = { bold: true, color: "#164E63" };
  sheet.getRange("B7:B14").format.wrapText = true;
  sheet.getRange("B7").format.numberFormat = "@";
  sheet.getRange("B13").format.numberFormat = "@";
  return sheet;
}

function finalizeSummaryFormulas(sheet, metadata) {
  sheet.getRange("B7").formulas = [[
    `=COUNTIF('区段证据'!$H$2:$H$${metadata.expectedGroupCodes.length + 1},"PASS")&"/${metadata.expectedGroupCodes.length} PASS"`,
  ]];
  sheet.getRange("B13").formulas = [[
    `=IF(AND(COUNTIF('区段证据'!$H$2:$H$${metadata.expectedGroupCodes.length + 1},"PASS")=${metadata.expectedGroupCodes.length},COUNTIF('一致性校验'!$D$3:$D$22,"FAIL")=0,B8="PASS"),"是","否")`,
  ]];
}

function createEvidenceSheet(workbook, metadata) {
  const columns = [
    { header: "group_ordinal", value: (row) => row.groupOrdinal, width: 12 },
    { header: "group_code", value: (row) => row.groupCode, width: 12 },
    { header: "source_run_id", value: (row) => row.sourceRunId, width: 52 },
    { header: "scenario_orders_csv", value: (row) => row.scenarioOrdersCsv, width: 90 },
    { header: "expected_rows", value: (row) => row.expectedRows, width: 14, format: "#,##0" },
    { header: "actual_rows", value: (row) => row.actualRows, width: 14, format: "#,##0" },
    { header: "distinct_users_and_orders", value: (row) => `${row.distinctUsers}/${row.distinctOrders}`, width: 24 },
    { header: "verdict", value: (row) => row.verdict, width: 12 },
    { header: "server_failure_rows", value: (row) => row.serverFailureRows, width: 18, format: "#,##0" },
    { header: "evidence_generated_at", value: (row) => row.evidenceGeneratedAt || "", width: 28 },
  ];
  const sheet = createDataSheet(
    workbook,
    "区段证据",
    columns,
    metadata.groupEvidence,
    "MembershipGroupEvidenceTable",
  );
  sheet.getRange(`H2:H${metadata.groupEvidence.length + 1}`).conditionalFormats.add(
    "containsText",
    { text: "PASS", format: { fill: "#DCFCE7", font: { bold: true, color: "#166534" } } },
  );
  return sheet;
}

function createConsistencySheet(workbook, metrics, metadata) {
  const sheet = workbook.worksheets.add("一致性校验");
  sheet.showGridLines = false;
  sheet.mergeCells("A1:D1");
  sheet.getRange("A1").values = [[
    `${metadata.expectedRowsPerGroup / 1000}K×${metadata.expectedGroupCodes.length} 数据一致性校验`,
  ]];
  sheet.getRange("A1:D1").format = {
    fill: "#083344",
    font: { bold: true, color: "#FFFFFF" },
  };
  const checks = [
    ["检查项", "期望值", "观察值", "结果"],
    ["订单数量", metrics.expected_records, metrics.order_count, null],
    ["回调数量", metrics.expected_records, metrics.callback_count, null],
    ["额度数量", metrics.expected_records, metrics.quota_count, null],
    ["不同订单数量", metrics.expected_records, metrics.distinct_order_count, null],
    ["不同回调数量", metrics.expected_records, metrics.distinct_callback_count, null],
    ["不同用户数量", metrics.expected_records, metrics.distinct_user_count, null],
    ["不同 quota 数量", metrics.expected_records, metrics.distinct_quota_count, null],
    ["缺失订单", 0, metrics.missing_order_count, null],
    ["缺失回调", 0, metrics.missing_callback_count, null],
    ["缺失 quota", 0, metrics.missing_quota_count, null],
    ["重复订单 ID", 0, metrics.duplicate_order_count, null],
    ["重复回调 ID", 0, metrics.duplicate_callback_count, null],
    ["重复 quota ID", 0, metrics.duplicate_quota_count, null],
    ["回调订单关联错误", 0, metrics.callback_order_mismatch_count, null],
    ["支付流水不一致", 0, metrics.provider_trade_mismatch_count, null],
    ["权益裁决不一致", 0, metrics.resolution_mismatch_count, null],
    ["会员等级错误", 0, metrics.membership_tier_mismatch_count, null],
    ["未裁决事实", 0, metrics.unresolved_fact_count, null],
    ["一致性失败行", 0, metrics.consistency_failure_count, null],
    ["Suite 总门禁", "PASS", metadata.suiteStatus, null],
  ];
  sheet.getRange("A2:D22").values = checks;
  sheet.getRange("D3").formulas = [["=IF(B3=C3,\"PASS\",\"FAIL\")"]];
  sheet.getRange("D3:D22").fillDown();
  sheet.getRange("A2:D2").format = {
    fill: "#155E75",
    font: { bold: true, color: "#FFFFFF" },
  };
  sheet.getRange("B3:C21").format.numberFormat = "#,##0";
  sheet.getRange("D3:D22").conditionalFormats.add(
    "containsText",
    { text: "FAIL", format: { fill: "#FEE2E2", font: { bold: true, color: "#991B1B" } } },
  );
  sheet.getRange("D3:D22").conditionalFormats.add(
    "containsText",
    { text: "PASS", format: { fill: "#DCFCE7", font: { bold: true, color: "#166534" } } },
  );
  setColumnWidth(sheet, 0, 22, 28);
  setColumnWidth(sheet, 1, 22, 16);
  setColumnWidth(sheet, 2, 22, 16);
  setColumnWidth(sheet, 3, 22, 14);

  sheet.freezePanes.freezeRows(2);
  return sheet;
}

function createFieldGuideSheet(workbook, metadata) {
  const sheet = workbook.worksheets.add("字段说明");
  const rows = [
    ["主题", "说明"],
    ["Hybrid ID", "membership_order.id、membership_payment_callback.id 和 order_id 是 16 字节 BYTEA；使用无填充 Base64URL 后固定为 22 个字符。"],
    ["BIGINT 公共 ID", "user_membership_quota.id 和 login_identity_id 使用正数 Long 的 8 字节大端序，再编码为 11 字符无填充 Base64URL。"],
    ["编码边界", "Base64URL 只是稳定编码，不是加密，也不替代认证、授权或防枚举公共 ID。"],
    ["原始 ID", "数据库 BYTEA 使用十六进制文本保存，BIGINT 使用十进制文本保存；原始列默认隐藏，可在 Excel 中取消隐藏审计。"],
    ["Excel 精度", "所有 ID 和 BIGINT 原值均按文本写入，避免 Excel 15 位有效数字限制导致截断或科学计数法。"],
    ["时间精度", "TIMESTAMPTZ(6) 按 YYYY-MM-DDTHH:mm:ss.ffffffZ 的 UTC 文本保存，避免 JavaScript Date 丢失后三位微秒。"],
    ["订单状态", "0=PENDING_PAYMENT，1=CLOSING，2=PAID，3=CANCELLED，4=CLOSED。"],
    ["会员等级", "0=FREE，1=GO，2=EDU，3=TEAM，4=PLUS，5=PRO，6=MAX。"],
    ["权益裁决", "APPLIED=已发放；NOT_GRANTED=未发放；REFUND_REQUIRED=需要退款且不得发放；LEGACY_NOT_GRANTED=历史订单不补发。"],
    ["当前结论", metadata.suiteStatus === "PASS"
      ? "八个区段、数据一致性与 Suite 总门禁均为 PASS，总览公式可显示完全测试完成。"
      : `八个区段的功能证据均为 PASS，但当前 Suite 为 ${metadata.suiteStatus}，因此本工作簿是失败运行快照，不是完全测试完成证明。`],
  ];
  sheet.getRange(`A1:B${rows.length}`).values = rows;
  sheet.showGridLines = false;
  sheet.getRange("A1:B1").format = {
    fill: "#155E75",
    font: { bold: true, color: "#FFFFFF" },
  };
  sheet.getRange(`A2:A${rows.length}`).format.font = { bold: true, color: "#164E63" };
  sheet.getRange(`A1:B${rows.length}`).format.wrapText = true;
  setColumnWidth(sheet, 0, rows.length, 24);
  setColumnWidth(sheet, 1, rows.length, 92);
  sheet.getRange(`A1:B${rows.length}`).format.autofitRows();
  sheet.freezePanes.freezeRows(1);
  return sheet;
}

async function renderVerificationPreviews(workbook, previewDirectory, sheetColumns) {
  await fs.mkdir(previewDirectory, { recursive: true });
  const previews = [];
  for (const sheetName of SHEET_NAMES) {
    const columnCount = Math.max(1, Math.min(sheetColumns.get(sheetName) ?? 8, 12));
    const preview = await workbook.render({
      sheetName,
      range: `A1:${columnName(columnCount - 1)}30`,
      scale: 1,
      format: "png",
    });
    const fileName = `${String(previews.length + 1).padStart(2, "0")}-${sheetName}.png`;
    const previewPath = path.join(previewDirectory, fileName);
    await fs.writeFile(previewPath, new Uint8Array(await preview.arrayBuffer()));
    previews.push(previewPath);
  }
  return previews;
}

async function inspectPersistedBoundaryRows(workbook, expectedRows) {
  const boundaries = [
    { sheetName: "ID映射", row: expectedRows + 1, lastColumn: "L", styleRange: `A${expectedRows + 1}:J${expectedRows + 1}` },
    { sheetName: "membership_order", row: expectedRows + 1, lastColumn: "Z", styleRange: `A${expectedRows + 1}:H${expectedRows + 1}` },
    { sheetName: "membership_payment_callback", row: expectedRows + 1, lastColumn: "P", styleRange: `A${expectedRows + 1}:F${expectedRows + 1}` },
    { sheetName: "user_membership_quota", row: expectedRows + 1, lastColumn: "O", styleRange: `A${expectedRows + 1}:G${expectedRows + 1}` },
  ];
  const inspections = [];
  for (const boundary of boundaries) {
    const values = await workbook.inspect({
      kind: "table",
      sheetId: boundary.sheetName,
      range: `A${boundary.row}:${boundary.lastColumn}${boundary.row}`,
      include: "values,formulas",
      tableMaxRows: 2,
      tableMaxCols: 26,
      maxChars: 8000,
    });
    const styles = await workbook.inspect({
      kind: "computedStyle",
      sheetId: boundary.sheetName,
      range: boundary.styleRange,
      maxChars: 8000,
    });
    if (!values.ndjson.trim() || !styles.ndjson.trim()) {
      throw new Error(`Persisted last-row inspection is empty for ${boundary.sheetName}.`);
    }
    inspections.push({
      sheetName: boundary.sheetName,
      row: boundary.row,
      valueInspection: values.ndjson,
      styleInspection: styles.ndjson,
    });
  }
  return inspections;
}

async function buildWorkbook(options) {
  const metadata = JSON.parse(await fs.readFile(options.metadata, "utf8"));
  if (
    !Array.isArray(metadata.expectedGroupCodes) ||
    metadata.expectedGroupCodes.join(",") !== GROUP_CODES.join(",")
  ) {
    throw new Error("Workbook metadata does not contain the immutable eight-group order.");
  }
  if (!Number.isInteger(metadata.expectedTotalRows) || metadata.expectedTotalRows <= 0) {
    throw new Error("Workbook metadata contains an invalid expected total row count.");
  }
  if (!Array.isArray(metadata.groupEvidence) || metadata.groupEvidence.length !== GROUP_CODES.length) {
    throw new Error("Workbook metadata must contain evidence for all eight groups.");
  }
  if (metadata.groupEvidence.some((group) => group.verdict !== "PASS")) {
    throw new Error("Every group verdict must be PASS before a workbook snapshot is exported.");
  }
  if (metadata.completionPolicy === "RequireSuitePass" && metadata.suiteStatus !== "PASS") {
    throw new Error("Completion policy requires a PASS suite verdict.");
  }

  // 先导入并编辑调用方提供的 Excel；占位表保证删除原有表时工作簿始终至少保留一张表。
  const inputBlob = await FileBlob.load(options["input-workbook"]);
  const workbook = await SpreadsheetFile.importXlsx(inputBlob);
  const existingSheetInspection = await workbook.inspect({
    kind: "sheet",
    include: "id,name",
    maxChars: 8000,
  });
  const existingSheetNames = existingSheetInspection.ndjson
    .split(/\r?\n/u)
    .filter(Boolean)
    .map((line) => JSON.parse(line))
    .map((record) => record.name)
    .filter((name) => typeof name === "string" && name.length > 0);
  if (existingSheetNames.length === 0) {
    throw new Error("Input workbook does not contain a worksheet to rebuild.");
  }
  const placeholderName = "__membership_export_build__";
  if (existingSheetNames.includes(placeholderName)) {
    throw new Error(`Input workbook already contains reserved worksheet ${placeholderName}.`);
  }
  const summarySheet = workbook.worksheets.add(placeholderName);
  for (const sheetName of existingSheetNames) {
    const existingSheet = workbook.worksheets.getItem(sheetName);
    existingSheet.deleteAllDrawings();
    existingSheet.delete();
  }
  summarySheet.name = "测试总览";

  const dataDirectory = options["data-directory"];
  const metricsRows = await readCsvObjects(path.join(dataDirectory, "metrics.csv"));
  requireExactRowCount(metricsRows, 1, "metrics");
  const metrics = Object.fromEntries(Object.entries(metricsRows[0]).map(([key, value]) => [key, numberFromDecimal(value, key)]));
  if (metrics.consistency_failure_count !== 0) {
    throw new Error("Database consistency metrics contain failed rows.");
  }
  let consistencyRows = await readCsvObjects(path.join(dataDirectory, "consistency.csv"));
  requireExactRowCount(consistencyRows, metadata.expectedTotalRows, "consistency detail");
  if (consistencyRows.some((row) => row.failure)) {
    throw new Error("Consistency detail contains at least one failed business row.");
  }
  const consistencyRowCount = consistencyRows.length;
  consistencyRows.length = 0;
  consistencyRows = null;
  await collectGarbage("一致性逐行校验完成并释放 CSV 数据");

  const sheetColumns = new Map();
  createSummarySheet(workbook, metadata, summarySheet);
  sheetColumns.set("测试总览", 8);

  const idMappingColumns = [
    { header: "membership_order_id_base64url", value: (row) => row.membership_order_id_base64url, width: 25, format: "@" },
    { header: "membership_payment_callback_id_base64url", value: (row) => row.membership_payment_callback_id_base64url, width: 25, format: "@" },
    { header: "user_membership_quota_id_base64url", value: (row) => row.user_membership_quota_id_base64url, width: 18, format: "@" },
    { header: "login_identity_public_id", value: (row) => row.login_identity_public_id, width: 18, format: "@" },
    { header: "group_code", value: (row) => row.group_code, width: 12 },
    { header: "source_run_id", value: (row) => row.source_run_id, width: 54 },
    { header: "membership_order_id_raw_hex", value: (row) => row.membership_order_id_raw_hex, width: 36, format: "@" },
    { header: "membership_payment_callback_id_raw_hex", value: (row) => row.membership_payment_callback_id_raw_hex, width: 36, format: "@" },
    { header: "user_membership_quota_id_raw", value: (row) => row.user_membership_quota_id_raw, width: 24, format: "@" },
    { header: "login_identity_id_raw", value: (row) => row.login_identity_id_raw, width: 24, format: "@" },
    { header: "scope_ordinal", value: (row) => numberFromDecimal(row.scope_ordinal, "scope_ordinal"), width: 14, format: "#,##0" },
    { header: "group_ordinal", value: (row) => numberFromDecimal(row.group_ordinal, "group_ordinal"), width: 14, format: "#,##0" },
  ];
  let idMapping = transformIdMapping(await readCsvObjects(path.join(dataDirectory, "id-mapping.csv")));
  requireExactRowCount(idMapping, metadata.expectedTotalRows, "ID mapping");
  const idMappingRowCount = idMapping.length;
  createDataSheet(workbook, "ID映射", idMappingColumns, idMapping, "MembershipIdMappingTable", [6, 7, 8, 9, 10, 11]);
  sheetColumns.set("ID映射", idMappingColumns.length);
  idMapping.length = 0;
  idMapping = null;
  await collectGarbage("ID映射工作表完成并释放 CSV 数据");

  const orderColumns = [
    { header: "id_base64url", value: (row) => row.order_id_base64url, width: 25, format: "@" },
    { header: "login_identity_public_id", value: (row) => row.login_identity_public_id, width: 18, format: "@" },
    { header: "group_code", value: (row) => row.group_code, width: 12 },
    { header: "source_run_id", value: (row) => row.source_run_id, width: 54 },
    { header: "status_name", value: (row) => row.status_name, width: 22 },
    { header: "membership_tier_name", value: (row) => row.membership_tier_name, width: 20 },
    { header: "id_raw_hex", value: (row) => row.order_id_raw_hex, width: 36, format: "@" },
    { header: "login_identity_id_raw", value: (row) => row.login_identity_id_raw, width: 24, format: "@" },
    { header: "membership_tier_code", value: (row) => row.membership_tier_code, width: 18, format: "@" },
    { header: "pay_amount_yuan", value: (row) => numberFromDecimal(row.pay_amount_yuan, "pay_amount_yuan"), width: 18, format: "0.00" },
    { header: "pay_type", value: (row) => row.pay_type, width: 14 },
    { header: "status_code", value: (row) => row.status_code, width: 14, format: "@" },
    { header: "idempotency_key", value: (row) => row.idempotency_key, width: 38, format: "@" },
    { header: "provider_trade_no", value: (row) => row.provider_trade_no, width: 56, format: "@" },
    { header: "payment_started_at_utc", value: (row) => row.payment_started_at_utc, width: 29, format: "@" },
    { header: "expires_at_utc", value: (row) => row.expires_at_utc, width: 29, format: "@" },
    { header: "closing_deadline_at_utc", value: (row) => row.closing_deadline_at_utc, width: 29, format: "@" },
    { header: "paid_at_utc", value: (row) => row.paid_at_utc, width: 29, format: "@" },
    { header: "entitlement_resolution", value: (row) => row.entitlement_resolution, width: 24 },
    { header: "entitlement_resolved_at_utc", value: (row) => row.entitlement_resolved_at_utc, width: 29, format: "@" },
    { header: "state_version_raw", value: (row) => row.state_version_raw, width: 20, format: "@" },
    { header: "created_at_utc", value: (row) => row.created_at_utc, width: 29, format: "@" },
    { header: "updated_at_utc", value: (row) => row.updated_at_utc, width: 29, format: "@" },
    { header: "wave_code", value: (row) => row.wave_code, width: 14 },
    { header: "trace_id", value: (row) => row.trace_id, width: 38, format: "@" },
    { header: "scope_ordinal", value: (row) => numberFromDecimal(row.scope_ordinal, "scope_ordinal"), width: 14, format: "#,##0" },
  ];
  let orders = transformOrders(await readCsvObjects(path.join(dataDirectory, "orders.csv")));
  requireExactRowCount(orders, metadata.expectedTotalRows, "membership_order");
  const orderRowCount = orders.length;
  createDataSheet(workbook, "membership_order", orderColumns, orders, "MembershipOrderTable", [6, 7]);
  sheetColumns.set("membership_order", orderColumns.length);
  orders.length = 0;
  orders = null;
  await collectGarbage("membership_order 工作表完成并释放 CSV 数据");

  const callbackColumns = [
    { header: "id_base64url", value: (row) => row.callback_id_base64url, width: 25, format: "@" },
    { header: "order_id_base64url", value: (row) => row.order_id_base64url, width: 25, format: "@" },
    { header: "group_code", value: (row) => row.group_code, width: 12 },
    { header: "source_run_id", value: (row) => row.source_run_id, width: 54 },
    { header: "id_raw_hex", value: (row) => row.callback_id_raw_hex, width: 36, format: "@" },
    { header: "order_id_raw_hex", value: (row) => row.order_id_raw_hex, width: 36, format: "@" },
    { header: "provider_trade_no", value: (row) => row.provider_trade_no, width: 56, format: "@" },
    { header: "trade_status", value: (row) => row.trade_status, width: 22 },
    { header: "paid_amount_yuan", value: (row) => numberFromDecimal(row.paid_amount_yuan, "paid_amount_yuan"), width: 18, format: "0.00" },
    { header: "paid_at_utc", value: (row) => row.paid_at_utc, width: 29, format: "@" },
    { header: "received_at_utc", value: (row) => row.received_at_utc, width: 29, format: "@" },
    { header: "resolution", value: (row) => row.resolution, width: 22 },
    { header: "resolved_at_utc", value: (row) => row.resolved_at_utc, width: 29, format: "@" },
    { header: "wave_code", value: (row) => row.wave_code, width: 14 },
    { header: "trace_id", value: (row) => row.trace_id, width: 38, format: "@" },
    { header: "scope_ordinal", value: (row) => numberFromDecimal(row.scope_ordinal, "scope_ordinal"), width: 14, format: "#,##0" },
  ];
  let callbacks = transformCallbacks(await readCsvObjects(path.join(dataDirectory, "callbacks.csv")));
  requireExactRowCount(callbacks, metadata.expectedTotalRows, "membership_payment_callback");
  const callbackRowCount = callbacks.length;
  createDataSheet(workbook, "membership_payment_callback", callbackColumns, callbacks, "MembershipPaymentCallbackTable", [4, 5]);
  sheetColumns.set("membership_payment_callback", callbackColumns.length);
  callbacks.length = 0;
  callbacks = null;
  await collectGarbage("membership_payment_callback 工作表完成并释放 CSV 数据");

  const quotaColumns = [
    { header: "quota_id_base64url", value: (row) => row.quota_id_base64url, width: 18, format: "@" },
    { header: "login_identity_public_id", value: (row) => row.login_identity_public_id, width: 18, format: "@" },
    { header: "group_code", value: (row) => row.group_code, width: 12 },
    { header: "source_run_id", value: (row) => row.source_run_id, width: 54 },
    { header: "membership_tier_name", value: (row) => row.membership_tier_name, width: 20 },
    { header: "id_raw", value: (row) => row.quota_id_raw, width: 24, format: "@" },
    { header: "login_identity_id_raw", value: (row) => row.login_identity_id_raw, width: 24, format: "@" },
    { header: "membership_tier_code", value: (row) => row.membership_tier_code, width: 18, format: "@" },
    { header: "quota_balance_minor_raw", value: (row) => row.quota_balance_minor_raw, width: 24, format: "@" },
    { header: "quota_period_started_at_utc", value: (row) => row.quota_period_started_at_utc, width: 29, format: "@" },
    { header: "quota_period_ends_at_utc", value: (row) => row.quota_period_ends_at_utc, width: 29, format: "@" },
    { header: "membership_expires_at_utc", value: (row) => row.membership_expires_at_utc, width: 29, format: "@" },
    { header: "wave_code", value: (row) => row.wave_code, width: 14 },
    { header: "trace_id", value: (row) => row.trace_id, width: 38, format: "@" },
    { header: "scope_ordinal", value: (row) => numberFromDecimal(row.scope_ordinal, "scope_ordinal"), width: 14, format: "#,##0" },
  ];
  let quotas = transformQuotas(await readCsvObjects(path.join(dataDirectory, "quotas.csv")));
  requireExactRowCount(quotas, metadata.expectedTotalRows, "user_membership_quota");
  const quotaRowCount = quotas.length;
  createDataSheet(workbook, "user_membership_quota", quotaColumns, quotas, "UserMembershipQuotaTable", [5, 6]);
  sheetColumns.set("user_membership_quota", quotaColumns.length);
  quotas.length = 0;
  quotas = null;
  await collectGarbage("user_membership_quota 工作表完成并释放 CSV 数据");

  createEvidenceSheet(workbook, metadata);
  sheetColumns.set("区段证据", 10);
  createConsistencySheet(workbook, metrics, metadata);
  sheetColumns.set("一致性校验", 4);
  finalizeSummaryFormulas(summarySheet, metadata);
  createFieldGuideSheet(workbook, metadata);
  sheetColumns.set("字段说明", 2);

  const sheetInspection = await workbook.inspect({ kind: "sheet", include: "id,name", maxChars: 8000 });
  for (const sheetName of SHEET_NAMES) {
    if (!sheetInspection.ndjson.includes(`"name":"${sheetName}"`)) {
      throw new Error(`Generated workbook is missing worksheet ${sheetName}.`);
    }
  }
  const formulaErrors = await workbook.inspect({
    kind: "match",
    searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
    options: { useRegex: true, maxResults: 300 },
    summary: "membership workbook final formula error scan",
  });
  const formulaErrorRecords = formulaErrors.ndjson
    .split(/\r?\n/u)
    .filter(Boolean)
    .map((line) => JSON.parse(line))
    .filter((record) => record.kind !== "notice");
  if (formulaErrorRecords.length > 0) {
    throw new Error("Generated workbook contains formula errors.");
  }

  const previews = await renderVerificationPreviews(
    workbook,
    options["preview-directory"],
    sheetColumns,
  );
  await fs.mkdir(path.dirname(options["output-workbook"]), { recursive: true });
  const output = await SpreadsheetFile.exportXlsx(workbook);
  await output.save(options["output-workbook"]);
  return {
    metadata,
    previews,
    orderRowCount,
    callbackRowCount,
    quotaRowCount,
    idMappingRowCount,
    consistencyRowCount,
  };
}

async function verifyPersistedWorkbook(options, buildResult) {
  const persistedBlob = await FileBlob.load(options["output-workbook"]);
  const persistedWorkbook = await SpreadsheetFile.importXlsx(persistedBlob);
  const persistedSheets = await persistedWorkbook.inspect({
    kind: "sheet",
    include: "id,name",
    maxChars: 8000,
  });
  for (const sheetName of SHEET_NAMES) {
    if (!persistedSheets.ndjson.includes(`"name":"${sheetName}"`)) {
      throw new Error(`Re-imported workbook is missing worksheet ${sheetName}.`);
    }
  }
  const persistedFormulaErrors = await persistedWorkbook.inspect({
    kind: "match",
    searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
    options: { useRegex: true, maxResults: 300 },
    summary: "re-imported membership workbook formula error scan",
  });
  const persistedFormulaErrorRecords = persistedFormulaErrors.ndjson
    .split(/\r?\n/u)
    .filter(Boolean)
    .map((line) => JSON.parse(line))
    .filter((record) => record.kind !== "notice");
  if (persistedFormulaErrorRecords.length > 0) {
    throw new Error("Re-imported workbook contains formula errors.");
  }
  const boundaryInspections = await inspectPersistedBoundaryRows(
    persistedWorkbook,
    buildResult.metadata.expectedTotalRows,
  );
  await fs.writeFile(
    options["verification-file"],
    `${JSON.stringify({
      sheets: SHEET_NAMES,
      expectedRows: buildResult.metadata.expectedTotalRows,
      orderRows: buildResult.orderRowCount,
      callbackRows: buildResult.callbackRowCount,
      quotaRows: buildResult.quotaRowCount,
      idMappingRows: buildResult.idMappingRowCount,
      consistencyRows: buildResult.consistencyRowCount,
      formulaErrorCount: 0,
      previews: buildResult.previews,
      boundaryInspections,
    }, null, 2)}\n`,
    "utf8",
  );
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const buildResult = await buildWorkbook(options);
  await collectGarbage("构建工作簿已导出，释放构建对象后开始重新导入验证");
  await verifyPersistedWorkbook(options, buildResult);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  });
}
