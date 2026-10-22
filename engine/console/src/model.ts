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

export interface IntegrationDraft {
  id: string;
  name?: string;
  input?: string;
  output?: string;
  activeVersion?: string;
  contract?: string;
  operationId?: string;
  target?: { baseUrl?: string; method?: string; path?: string };
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

/** Published versions of one integration, newest first (engine MappingApiController). */
export interface VersionList {
  activeVersion: string | null;
  versions: { version: string; publishedAt: string | null }[];
}

export type Auth =
  | { type: 'bearer' }
  | { type: 'apiKey'; in: 'header' | 'query'; name: string };

export interface IntegrationSettings {
  baseUrl: string;
  servers: string[];
  auth: Auth | null;
  secretEnvName: string;
  secretInEnv: boolean;
  secretInFile: boolean;
}
