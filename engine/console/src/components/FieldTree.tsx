import { Fragment } from 'react';
import { ActionIcon, Button, Checkbox, Group, NativeSelect, Table, Text, TextInput, Tooltip } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import type { Field, FieldType } from '../model';
import { ALL_TYPES, SIMPLE_TYPES, TYPE_LABELS } from '../model';
import type { TreePath } from '../tree';
import { addField, children, newFieldId, removeField, setItemType, updateField } from '../tree';

interface Props {
  definitionId: string;
  fields: Field[];
  onChange: (fields: Field[]) => void;
}

const typeOptions = ALL_TYPES.map((t) => ({ value: t, label: TYPE_LABELS[t] }));
const itemTypeOptions = ALL_TYPES.filter((t) => t !== 'list').map((t) => ({ value: t, label: TYPE_LABELS[t] }));

/**
 * The payload definition editor (D-13): one row per field with name | type | required | format | value. Objects
 * and lists of objects get "+ field"; a list asks for its item type. A field with a value is a constant (R11).
 */
export function FieldTree({ definitionId, fields, onChange }: Props) {
  const add = (parent: TreePath) =>
    onChange(addField(fields, parent, { id: newFieldId(definitionId), name: '', type: 'text' }));

  return (
    <>
      <Table withTableBorder verticalSpacing={4} aria-label="Fields">
        <Table.Thead>
          <Table.Tr>
            <Table.Th w="34%">Name</Table.Th>
            <Table.Th w="22%">Type</Table.Th>
            <Table.Th w={90}>Required</Table.Th>
            <Table.Th w="14%">Format</Table.Th>
            <Table.Th w="16%">Constant value</Table.Th>
            <Table.Th w={70} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {fields.length === 0 && (
            <Table.Tr>
              <Table.Td colSpan={6}>
                <Text c="dimmed" size="sm">
                  No fields yet. Add one, or paste a JSON sample above.
                </Text>
              </Table.Td>
            </Table.Tr>
          )}
          <Rows fields={fields} path={[]} depth={0} all={fields} onChange={onChange} onAdd={add} />
        </Table.Tbody>
      </Table>
      <Button variant="subtle" leftSection={<IconPlus size={16} />} onClick={() => add([])} mt="xs" style={{ alignSelf: 'flex-start' }}>
        Add field
      </Button>
    </>
  );
}

interface RowsProps {
  fields: Field[];
  path: TreePath;
  depth: number;
  all: Field[];
  onChange: (fields: Field[]) => void;
  onAdd: (parent: TreePath) => void;
}

function Rows({ fields, path, depth, all, onChange, onAdd }: RowsProps) {
  return (
    <>
      {fields.map((f, i) => {
        const here = [...path, i];
        const kids = children(f);
        const label = f.name || 'field';
        return (
          <Fragment key={f.id ?? i}>
            <Table.Tr>
              <Table.Td>
                <Group gap={4} wrap="nowrap" pl={depth * 24}>
                  {depth > 0 && (
                    <Text c="dimmed" size="sm" aria-hidden>
                      └
                    </Text>
                  )}
                  <TextInput
                    size="xs"
                    ff="monospace"
                    value={f.name ?? ''}
                    placeholder="name"
                    aria-label="Field name"
                    onChange={(e) => onChange(updateField(all, here, { name: e.currentTarget.value }))}
                    error={!f.name?.trim()}
                    style={{ flex: 1 }}
                  />
                </Group>
              </Table.Td>
              <Table.Td>
                <Group gap={4} wrap="nowrap">
                  <NativeSelect
                    size="xs"
                    data={typeOptions}
                    value={f.type}
                    aria-label={`Type of ${label}`}
                    onChange={(e) => onChange(updateField(all, here, { type: e.currentTarget.value as FieldType }))}
                  />
                  {f.type === 'list' && (
                    <NativeSelect
                      size="xs"
                      data={itemTypeOptions}
                      value={f.items?.type ?? 'text'}
                      aria-label={`Item type of ${label}`}
                      leftSection={<Text size="xs">of</Text>}
                      onChange={(e) => onChange(setItemType(all, here, e.currentTarget.value as FieldType))}
                    />
                  )}
                </Group>
              </Table.Td>
              <Table.Td>
                <Checkbox
                  checked={!!f.required}
                  aria-label={`${label} is required`}
                  disabled={f.value !== undefined}
                  onChange={(e) => onChange(updateField(all, here, { required: e.currentTarget.checked }))}
                />
              </Table.Td>
              <Table.Td>
                {(f.type === 'text' || f.type === 'date' || f.type === 'datetime') && (
                  <TextInput
                    size="xs"
                    value={f.format ?? ''}
                    placeholder="optional"
                    aria-label={`Format of ${label}`}
                    onChange={(e) => onChange(updateField(all, here, { format: e.currentTarget.value || undefined }))}
                  />
                )}
              </Table.Td>
              <Table.Td>
                {SIMPLE_TYPES.includes(f.type) && (
                  <Tooltip label="A constant: callers never send it; it can be linked like any other field" withArrow>
                    <TextInput
                      size="xs"
                      ff="monospace"
                      value={f.value === undefined ? '' : String(f.value)}
                      placeholder="—"
                      aria-label={`Constant value of ${label}`}
                      onChange={(e) =>
                        onChange(updateField(all, here, { value: toValue(e.currentTarget.value, f.type), required: false }))
                      }
                    />
                  </Tooltip>
                )}
              </Table.Td>
              <Table.Td>
                <Group gap={2} wrap="nowrap">
                  {kids && (
                    <Tooltip label={`Add a field inside ${label}`} withArrow>
                      <ActionIcon variant="subtle" onClick={() => onAdd(here)} aria-label={`Add a field inside ${label}`}>
                        <IconPlus size={16} />
                      </ActionIcon>
                    </Tooltip>
                  )}
                  <Tooltip label={`Delete ${label}`} withArrow>
                    <ActionIcon variant="subtle" color="red" onClick={() => onChange(removeField(all, here))} aria-label={`Delete ${label}`}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Tooltip>
                </Group>
              </Table.Td>
            </Table.Tr>
            {kids && <Rows fields={kids} path={here} depth={depth + 1} all={all} onChange={onChange} onAdd={onAdd} />}
          </Fragment>
        );
      })}
    </>
  );
}

/** Keeps constants typed: numbers as numbers, yes/no as booleans; empty removes the constant. */
function toValue(raw: string, type: FieldType): unknown {
  if (raw === '') return undefined;
  if ((type === 'integer' || type === 'number') && raw.trim() !== '' && !Number.isNaN(Number(raw))) return Number(raw);
  if (type === 'boolean' && (raw === 'true' || raw === 'false')) return raw === 'true';
  return raw;
}
