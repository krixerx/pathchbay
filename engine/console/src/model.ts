// Shapes of the config files and API responses (engine: PayloadDefinition, ConsoleApiController).

export type FieldType = 'text' | 'number' | 'integer' | 'boolean' | 'date' | 'datetime' | 'object' | 'list';

export const SIMPLE_TYPES: FieldType[] = ['text', 'number', 'integer', 'boolean', 'date', 'datetime'];
export const ALL_TYPES: FieldType[] = [...SIMPLE_TYPES, 'object', 'list'];

/** One vocabulary in every view (D-4). */
export const TYPE_LABELS: Record<FieldType, string> = {
  text: 'text',
  number: 'number',
  integer: 'whole number',
  boolean: 'yes/no',
  date: 'date',
  datetime: 'date-time',
  object: 'object',
  list: 'list',
};

export interface Field {
  id?: string;
  name?: string;
  type: FieldType;
  required?: boolean;
  format?: string;
  /** A field with a value is a constant (R11). */
  value?: unknown;
  fields?: Field[];
  items?: Field;
}

export interface PayloadDefinition {
  id: string;
  name: string;
  fields: Field[];
}

export interface DefinitionSummary {
  id: string;
  name: string;
  fieldCount: number;
  usedBy: string[];
}

export interface IntegrationSummary {
  id: string;
  name?: string;
  input?: string;
  output?: string;
  method?: string;
  activeVersion?: string;
  status: 'live' | 'draft' | 'broken';
  problem?: string;
}

/**
 * Wire formats (D-24): how the request body goes to the external system and what callers get back. Missing means
 * JSON. An XML document needs a root element name.
 */
export interface Formats {
  request?: 'json' | 'xml';
  requestRoot?: string;
  output?: 'json' | 'xml';
  outputRoot?: string;
}

export interface IntegrationDraft {
  id: string;
  name?: string;
  input?: string;
  output?: string;
  activeVersion?: string;
  contract?: string;
  operationId?: string;
  /** `connection` gives the base URL in each environment (D-26); a literal `baseUrl` only in older files. */
  target?: { baseUrl?: string; method?: string; path?: string; connection?: string };
  formats?: Formats;
  /** Headers added on the board for the external call (D-27); request link targets like contract headers. */
  addedHeaders?: string[];
  request?: { links: unknown[] };
  response?: { links: unknown[] };
  /** Only in the answer to choosing an operation: what "fill from the API later" created. */
  notes?: string[];
}

export interface ContractSummary {
  id: string;
  name: string;
  operationCount: number;
  servers: string[];
  warningCount: number;
  importedAt: string;
  usedBy: string[];
  /** Its connection's base URL in this environment (D-26), null when not set. */
  baseUrl: string | null;
  baseUrlInEnv: boolean;
}

/**
 * A header the connection sends on every call (D-27). `value` is null for a secret one, whose value never reaches
 * the console; `saved` says whether the engine has a value of its own, `inEnv` whether the variable sets it.
 */
export interface ConnectionHeader {
  name: string;
  secret: boolean;
  value: string | null;
  saved: boolean;
  envName: string;
  inEnv: boolean;
}

/** Where a Swagger configuration's external system is in this environment (D-26), and the headers it gets (D-27). */
export interface Connection {
  id: string;
  /** The address in use: the environment variable's, else the saved one. */
  baseUrl: string | null;
  savedUrl: string | null;
  envName: string;
  inEnv: boolean;
  servers: string[];
  usedBy: string[];
  headers: ConnectionHeader[];
}

export interface ContractParameter {
  name: string;
  in: 'path' | 'query' | 'header';
  required: boolean;
  type: string;
}

export interface ContractOperation {
  id: string;
  method: string;
  path: string;
  summary?: string;
  parameters: ContractParameter[];
  requestBody?: Field;
  responseStatus?: string;
  response?: Field;
}

export interface Contract {
  id: string;
  name: string;
  sourceFile: string;
  importedAt: string;
  servers: string[];
  operations: ContractOperation[];
  warnings: string[];
}

/** A Test drawer run (engine TestRunner): where it stopped, and what each step produced. */
export interface TestResult {
  stage: 'setup' | 'links' | 'input' | 'request' | 'response' | 'output' | 'done';
  problems: string[];
  request?: { method: string; url: string; headers: Record<string, string>; body: unknown };
  response?: { status: number; body: unknown };
  output?: unknown;
  mock: boolean;
}

/** One live call from the engine's call log (D-22); payload parts are missing when MAPPER_CALL_LOG=off. */
export interface CallRecord {
  at: string;
  version: string | null;
  status: number;
  durationMs: number;
  stage: TestResult['stage'];
  problems: string[];
  input?: unknown;
  request?: TestResult['request'];
  response?: TestResult['response'];
  output?: unknown;
}

export interface CallList {
  enabled: boolean;
  calls: CallRecord[];
}

/** Published versions of one integration, newest first (engine MappingApiController). */
export interface VersionList {
  activeVersion: string | null;
  versions: { version: string; publishedAt: string | null }[];
}

export type Auth =
  | { type: 'bearer' }
  | { type: 'apiKey'; in: 'header' | 'query'; name: string };

export interface IntegrationSettings {
  /** The connection the integration calls through; null only in files from before connections. */
  connection: string | null;
  baseUrl: string | null;
  auth: Auth | null;
  secretEnvName: string;
  secretInEnv: boolean;
  secretInFile: boolean;
}
