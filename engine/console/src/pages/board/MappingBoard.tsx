import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Box,
  Button,
  Chip,
  Group,
  Modal,
  Paper,
  ScrollArea,
  SegmentedControl,
  Skeleton,
  Stack,
  Switch,
  Text,
  Textarea,
  TextInput,
  Tooltip,
  UnstyledButton,
} from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { useVirtualizer } from '@tanstack/react-virtual';
import { IconAlertTriangle, IconCheck, IconSearch, IconX } from '@tabler/icons-react';
import { api } from '../../api';
import { CallSteps } from '../../components/CallSteps';
import type { ContractOperation, IntegrationDraft, PayloadDefinition, TestResult } from '../../model';
import { TYPE_LABELS } from '../../model';
import type { Filter, Link, LinkState, Row, Tab } from '../../board';
import { publishSummary } from '../../publish';
import {
  addLink,
  canLink,
  exampleInput,
  emptySideText,
  filterRows,
  linkStates,
  missingRows,
  nothingLinked,
  withFreeFormRows,
  payloadRows,
  requestRows,
  requiredUnmapped,
  responseRows,
} from '../../board';
import { meaning, statusText } from '../../theme';
import { FormatSwitch } from './FormatSwitch';

const ROW_HEIGHT = 34;
const GUTTER = 120;
/**
 * The trees' height: the viewport less the app header, the panel's title, search and filters, the add-header row and
 * a margin, so the whole board fits the screen once it is scrolled to the top. Never below 360px.
 */
const TREE_HEIGHT = 'max(360px, calc(100dvh - 270px))';
const MISSING_TEXT = 'This field no longer exists. Remove the link.';

/** Where one tree's rows are, for drawing lines: row centre y per key, relative to the gutter's top. */
interface Layout {
  ys: Map<string, number>;
  top: number;
  bottom: number;
}

interface BoardData {
  input: PayloadDefinition | null;
  output: PayloadDefinition | null;
  operation: ContractOperation | null;
}

/**
 * The mapping board (D-4, D-6, D-7, D-11, D-12, D-15, D-17, D-20): two field trees with a line from every linked
 * field to its partner. Click a field on the left, then the field on the right it goes into.
 */
export function MappingBoard(props: {
  draft: IntegrationDraft;
  onChange: (d: IntegrationDraft) => void;
  tab: Tab;
  onTab: (t: Tab) => void;
  testOpen: boolean;
  /** How many problems block publishing, for the Publish button (same count as the publish dialog). */
  onProblems?: (count: number) => void;
}) {
  const { draft, tab, testOpen } = props;
  const wide = useMediaQuery('(min-width: 1024px)');
  const [data, setData] = useState<BoardData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [links, setLinks] = useState<Record<Tab, Link[]>>({
    request: (draft.request?.links as Link[]) ?? [],
    response: (draft.response?.links as Link[]) ?? [],
  });
  const [added, setAdded] = useState<string[]>(draft.addedHeaders ?? []);
  const [selected, setSelected] = useState<string | null>(null);
  const selectedRef = useRef<string | null>(null);
  selectedRef.current = selected;
  // What just happened, in the hint line under the tabs (a live region): a link made, or why one can't be made.
  const [notice, setNotice] = useState<{ text: string; broken: boolean } | null>(null);
  // Bumped to move keyboard focus into a tree: the target tree after choosing a source, the source tree after linking.
  const [focusSignal, setFocusSignal] = useState({ source: 0, target: 0 });
  const focusTree = (side: 'source' | 'target') => setFocusSignal((old) => ({ ...old, [side]: old[side] + 1 }));
  const [filters, setFilters] = useState<Record<'source' | 'target', Filter>>({ source: 'all', target: 'all' });
  const [saved, setSaved] = useState<'idle' | 'saving' | 'saved'>('idle');
  // Why the last change could not be saved; the change itself is undone on the board.
  const [saveError, setSaveError] = useState<string | null>(null);
  // The links as the engine last confirmed them, to go back to when a save fails.
  const confirmed = useRef<Record<Tab, Link[]>>({
    request: (draft.request?.links as Link[]) ?? [],
    response: (draft.response?.links as Link[]) ?? [],
  });
  const [lastTest, setLastTest] = useState<{ ok: boolean; at: string } | null>(null);
  const gutter = useRef<HTMLDivElement>(null);
  const [layouts, setLayouts] = useState<{ source?: Layout; target?: Layout }>({});
  const onLayout = useCallback((side: 'source' | 'target', l: Layout) => setLayouts((old) => ({ ...old, [side]: l })), []);

  useEffect(() => {
    api.board(draft.id).then(setData, (e: Error) => setError(e.message));
  }, [draft.id]);

  // Esc cancels a pending source selection (D-17) and puts keyboard focus back on that source.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || !selectedRef.current) return;
      setSelected(null);
      setNotice(null);
      setFocusSignal((old) => ({ ...old, source: old.source + 1 }));
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const sides = useMemo(() => {
    if (!data) return null;
    return tab === 'request'
      ? { sources: payloadRows(data.input), targets: requestRows(data.operation, added), left: 'Your Input', right: 'API request' }
      : { sources: responseRows(data.operation), targets: payloadRows(data.output), left: 'API response', right: 'Your Output' };
  }, [data, tab, added]);

  const current = links[tab];
  const requestFormat = draft.formats?.request;
  const states = useMemo(
    () => (sides ? linkStates(current, sides.sources, sides.targets, requestFormat) : []),
    [current, sides, requestFormat],
  );
  const unmapped = useMemo(() => (sides ? requiredUnmapped(sides.targets, current) : []), [sides, current]);
  const redCount = states.filter((s) => s.kind !== 'ok').length;
  const otherTab: Tab = tab === 'request' ? 'response' : 'request';
  // Fields that no longer exist get a row of their own, so a broken link is drawn like any other (red, dashed).
  const shownSides = useMemo(() => {
    if (!sides) return null;
    // Linked paths inside a free-form object of the contract get a row under that object, so their lines are drawn.
    const sources = withFreeFormRows(sides.sources, current.map((l) => l.from));
    const targets = withFreeFormRows(sides.targets, current.map((l) => l.to));
    const ghosts = missingRows(current, sides.sources, sides.targets);
    return { sources: [...sources, ...ghosts.sources], targets: [...targets, ...ghosts.targets] };
  }, [sides, current]);
  const problemKeys = useMemo(() => {
    const keys = new Set<string>();
    current.forEach((l, i) => {
      if (states[i]?.kind !== 'ok') {
        keys.add(l.from);
        keys.add(l.to);
      }
    });
    return keys;
  }, [current, states]);
  const showProblems = () => setFilters({ source: 'problems', target: 'problems' });

  const { onProblems } = props;
  const blockingCount = useMemo(
    () => (data ? publishSummary(draft, data.input, data.output, data.operation).blocking.length : 0),
    [data, draft],
  );
  useEffect(() => {
    if (data) onProblems?.(blockingCount);
  }, [data, blockingCount, onProblems]);

  // Autosave on every change (D-1). The board shows the change at once; if the save fails it goes back to what the
  // engine last confirmed and the status bar says the change was not saved, so nothing looks saved that isn't.
  const save = useCallback(
    async (next: Record<Tab, Link[]>) => {
      setLinks(next);
      setSaved('saving');
      setSaveError(null);
      try {
        const d = await api.saveLinks(draft.id, next.request, next.response);
        confirmed.current = next;
        props.onChange(d);
        setSaved('saved');
      } catch (e) {
        setLinks(confirmed.current);
        setSaveError((e as Error).message);
        setSaved('idle');
      }
    },
    [draft.id, props],
  );

  if (!wide) {
    return (
      <Alert color={meaning.warning} icon={<IconAlertTriangle />} mt="lg">
        Use a wider screen to map fields (at least 1024 pixels).
      </Alert>
    );
  }
  if (!data || !sides || !shownSides) return error ? <ErrorBox text={error} /> : <Skeleton height={300} mt="lg" />;
  if (!data.operation) {
    return (
      <Text mt="lg">
        Choose the external system and the operation this integration calls. An Input and Output left empty are created from
        the operation, already linked.
      </Text>
    );
  }
  if (!data.input || !data.output) {
    return <Text mt="lg">Choose {!data.input ? 'the Input' : 'the Output'} above to map fields.</Text>;
  }

  const sourceRow = selected ? sides.sources.find((r) => r.key === selected) ?? null : null;

  // With the keyboard, choosing a source moves focus to the target tree and linking moves it back, so mapping is
  // arrows and Enter on the left, arrows and Enter on the right.
  const clickSource = (r: Row, byKey: boolean) => {
    if (r.missing) return;
    const next = selected === r.key ? null : r.key;
    setSelected(next);
    setNotice(null);
    if (next && byKey) focusTree('target');
  };
  const clickTarget = (r: Row, byKey: boolean) => {
    if (!sourceRow) {
      setNotice({ text: `Choose a field on the left first, then “${r.label}”.`, broken: false });
      return;
    }
    if (canLink(sourceRow, r, current, sides.sources, sides.targets, requestFormat)) return;
    save({ ...links, [tab]: addLink(current, sourceRow.key, r.key) });
    setSelected(null);
    setNotice({ text: `Linked “${sourceRow.label}” to “${r.label}”.`, broken: false });
    if (byKey) focusTree('source');
  };
  // A row that can't be used says why, for keyboard users too (the tooltip only shows on hover).
  const blocked = (r: Row, reason: string) =>
    setNotice({ text: sourceRow && !r.missing ? `Can't link “${sourceRow.label}” to “${r.label}”: ${reason}` : reason, broken: true });
  const remove = (l: Link) => save({ ...links, [tab]: current.filter((x) => x !== l) });

  // Headers added on the board (D-27); the engine drops the links of a removed one, so links follow its answer.
  const saveHeaders = async (next: string[]) => {
    const d = await api.saveHeaders(draft.id, next);
    setAdded(d.addedHeaders ?? []);
    const request = (d.request?.links as Link[]) ?? [];
    confirmed.current = { ...confirmed.current, request };
    setLinks((old) => ({ ...old, request }));
    setSaveError(null);
    props.onChange(d);
  };
  const removeHeader = (r: Row) =>
    saveHeaders(added.filter((h) => `headers.${h}` !== r.key)).catch((e: Error) => setSaveError(e.message));

  return (
    <Stack gap="sm" mt="md">
      <StatusBar
        unmapped={unmapped.length}
        nothingLinked={nothingLinked(current, sides.sources, sides.targets)}
        red={redCount}
        other={{ tab: otherTab, red: linkStates(links[otherTab], ...otherSides(data, otherTab, added), requestFormat).filter((s) => s.kind !== 'ok').length }}
        lastTest={lastTest}
        saved={saved}
        saveError={saveError}
        onShowUnmapped={() => document.getElementById(`row-target-${unmapped[0]?.key}`)?.scrollIntoView({ block: 'center' })}
        onShowProblems={showProblems}
        onSwitch={() => {
          props.onTab(otherTab);
          showProblems();
        }}
      />

      <Group justify="space-between" wrap="nowrap">
        <SegmentedControl
          value={tab}
          onChange={(v) => {
            props.onTab(v as Tab);
            setSelected(null);
            setNotice(null);
          }}
          data={[
            { value: 'request', label: 'Input → API request' },
            { value: 'response', label: 'API response → Output' },
          ]}
        />
        <FormatSwitch draft={draft} tab={tab} onChange={props.onChange} onError={setSaveError} />
      </Group>

      <Text size="sm" c={notice?.broken ? statusText.broken : 'dimmed'} aria-live="polite">
        {notice
          ? notice.text
          : sourceRow
            ? `Now choose the field on the right that “${sourceRow.label}” goes into. Esc cancels.`
            : 'Click a field on the left, then the field on the right it goes into. With the keyboard: arrow keys to move, Enter to choose.'}
      </Text>

      <Group align="stretch" gap={0} wrap="nowrap">
        <TreePanel
          title={sides.left}
          rows={shownSides.sources}
          side="source"
          links={current}
          states={states}
          selected={selected}
          onClick={clickSource}
          onBlocked={blocked}
          focusSignal={focusSignal.source}
          isDisabled={(r) => (r.missing ? MISSING_TEXT : null)}
          filter={filters.source}
          onFilter={(f) => setFilters((old) => ({ ...old, source: f }))}
          problemKeys={problemKeys}
          emptyText={emptySideText(tab === 'request' ? 'input' : 'response', data.operation)}
          anchor={gutter}
          onLayout={onLayout}
        />
        <div ref={gutter} style={{ width: GUTTER, flexShrink: 0, position: 'relative' }}>
          <Lines
            links={current}
            states={states}
            source={layouts.source}
            target={layouts.target}
            selected={selected}
            sources={shownSides.sources}
            targets={shownSides.targets}
            onRemove={remove}
          />
        </div>
        <TreePanel
          title={sides.right}
          rows={shownSides.targets}
          side="target"
          links={current}
          states={states}
          selected={null}
          onClick={clickTarget}
          onBlocked={blocked}
          focusSignal={focusSignal.target}
          isDisabled={(r) =>
            r.missing ? MISSING_TEXT : sourceRow ? canLink(sourceRow, r, current, sides.sources, sides.targets, requestFormat) : null
          }
          filter={filters.target}
          onFilter={(f) => setFilters((old) => ({ ...old, target: f }))}
          problemKeys={problemKeys}
          emptyText={emptySideText(tab === 'request' ? 'request' : 'output', data.operation)}
          unmapped={new Set(unmapped.map((r) => r.key))}
          anchor={gutter}
          onLayout={onLayout}
          onRemoveRow={removeHeader}
          footer={tab === 'request' && <AddHeader onAdd={(name) => saveHeaders([...added, name])} />}
        />
        {testOpen && (
          <TestDrawer
            slug={draft.id}
            input={data.input}
            method={data.operation.method}
            onResult={(r) => setLastTest({ ok: r.stage === 'done', at: new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) })}
          />
        )}
      </Group>

      <LinkList links={current} states={states} sources={sides.sources} targets={sides.targets} onRemove={remove} />
      {error && <ErrorBox text={error} />}
    </Stack>
  );
}

function otherSides(data: BoardData, tab: Tab, added: string[]): [Row[], Row[]] {
  return tab === 'request'
    ? [payloadRows(data.input), requestRows(data.operation, added)]
    : [responseRows(data.operation), payloadRows(data.output)];
}

function StatusBar(props: {
  unmapped: number;
  nothingLinked: boolean;
  red: number;
  other: { tab: Tab; red: number };
  lastTest: { ok: boolean; at: string } | null;
  saved: 'idle' | 'saving' | 'saved';
  saveError: string | null;
  onShowUnmapped: () => void;
  onShowProblems: () => void;
  onSwitch: () => void;
}) {
  return (
    <Paper withBorder p="xs" role="status">
      <Group gap="lg">
        {props.unmapped > 0 ? (
          <UnstyledButton onClick={props.onShowUnmapped}>
            <Group gap={4}>
              <IconAlertTriangle size={16} color={statusText.warning} />
              <Text size="sm" c={statusText.warning}>
                {props.unmapped} required field{props.unmapped === 1 ? '' : 's'} unmapped ▸
              </Text>
            </Group>
          </UnstyledButton>
        ) : props.nothingLinked ? (
          <Group gap={4}>
            <IconAlertTriangle size={16} color={statusText.warning} />
            <Text size="sm" c={statusText.warning}>
              No fields are linked on this tab yet
            </Text>
          </Group>
        ) : (
          // Neutral, not green: green means only "test passed" (DESIGN.md).
          <Group gap={4}>
            <IconCheck size={16} color="var(--mantine-color-dimmed)" aria-hidden />
            <Text size="sm" c="dimmed">
              All required fields mapped
            </Text>
          </Group>
        )}
        {props.red > 0 && (
          <UnstyledButton onClick={props.onShowProblems}>
            <Group gap={4}>
              <IconX size={16} color={statusText.broken} />
              <Text size="sm" c={statusText.broken}>
                {props.red} link{props.red === 1 ? '' : 's'} can't be published ▸
              </Text>
            </Group>
          </UnstyledButton>
        )}
        {props.other.red > 0 && (
          <UnstyledButton onClick={props.onSwitch}>
            <Text size="sm" c={statusText.broken}>
              {props.other.red} problem{props.other.red === 1 ? '' : 's'} on the other tab ▸
            </Text>
          </UnstyledButton>
        )}
        {props.lastTest && (
          <Group gap={4}>
            {props.lastTest.ok ? <IconCheck size={16} color={statusText.passed} /> : <IconX size={16} color={statusText.broken} />}
            <Text size="sm" c={props.lastTest.ok ? statusText.passed : statusText.broken}>
              Last test {props.lastTest.ok ? 'passed' : 'failed'} {props.lastTest.at}
            </Text>
          </Group>
        )}
        {props.saveError ? (
          <Group gap={4} ml="auto" role="alert">
            <IconX size={16} color={statusText.broken} />
            <Text size="sm" c={statusText.broken}>
              Not saved: {props.saveError} The change was undone.
            </Text>
          </Group>
        ) : (
          <Text size="sm" c="dimmed" ml="auto">
            {props.saved === 'saving' ? 'Saving…' : props.saved === 'saved' ? '✓ Saved' : ''}
          </Text>
        )}
      </Group>
    </Paper>
  );
}

type Item = { kind: 'group'; label: string } | { kind: 'row'; row: Row };

function TreePanel(props: {
  title: string;
  rows: Row[];
  side: 'source' | 'target';
  links: Link[];
  states: LinkState[];
  selected: string | null;
  /** A row chosen by click or by Enter/Space (`byKey`). */
  onClick: (r: Row, byKey: boolean) => void;
  /** Enter/Space on a row that can't be used, with the reason. */
  onBlocked: (r: Row, reason: string) => void;
  /** Changes when the board wants keyboard focus in this tree (on its current row). */
  focusSignal: number;
  isDisabled: (r: Row) => string | null;
  filter: Filter;
  onFilter: (f: Filter) => void;
  /** The rows at either end of a link that can't be published. */
  problemKeys: Set<string>;
  /** What the tree says when it has no rows at all. */
  emptyText: string;
  unmapped?: Set<string>;
  anchor: React.RefObject<HTMLDivElement | null>;
  onLayout: (side: 'source' | 'target', l: Layout) => void;
  /** Removes a row added on the board (D-27); only added rows get the button. */
  onRemoveRow?: (r: Row) => void;
  footer?: React.ReactNode;
}) {
  const [query, setQuery] = useState('');
  const { problemKeys } = props;
  const hasProblems = problemKeys.size > 0;
  // Once the last problem is fixed, the Problems filter would show nothing: fall back to all fields.
  const filter: Filter = props.filter === 'problems' && !hasProblems ? 'all' : props.filter;
  const parent = useRef<HTMLDivElement>(null);
  const linkedKeys = useMemo(
    () => new Set(props.links.map((l) => (props.side === 'source' ? l.from : l.to))),
    [props.links, props.side],
  );
  const shown = useMemo(
    () => filterRows(props.rows, query, filter, linkedKeys, problemKeys),
    [props.rows, query, filter, linkedKeys, problemKeys],
  );
  const items = useMemo(() => {
    const out: Item[] = [];
    let group: string | undefined;
    for (const r of shown) {
      const g = r.missing ? 'No longer exists' : r.group;
      if (g && g !== group && g !== 'Response') out.push({ kind: 'group', label: g });
      group = g;
      out.push({ kind: 'row', row: r });
    }
    return out;
  }, [shown]);
  const virtual = useVirtualizer({ count: items.length, getScrollElement: () => parent.current, estimateSize: () => ROW_HEIGHT, overscan: 12 });

  // Keyboard (WAI-ARIA tree pattern): the tree is one Tab stop, its current row; arrows, Home and End move. Rows are
  // virtualized, so a row is scrolled into view first and focused once it is rendered.
  const rowKeys = useMemo(() => items.flatMap((it) => (it.kind === 'row' ? [it.row.key] : [])), [items]);
  const [current, setCurrent] = useState<string | null>(null);
  const currentKey = current && rowKeys.includes(current) ? current : (rowKeys[0] ?? null);
  const pendingFocus = useRef<string | null>(null);
  const [, rerender] = useState(0);
  const moveTo = (key: string) => {
    setCurrent(key);
    pendingFocus.current = key;
    virtual.scrollToIndex(items.findIndex((it) => it.kind === 'row' && it.row.key === key), { align: 'auto' });
    rerender((n) => n + 1);
  };
  useEffect(() => {
    const key = pendingFocus.current;
    if (!key) return;
    const el = document.getElementById(`row-${props.side}-${key}`);
    if (el) {
      el.focus({ preventScroll: true });
      pendingFocus.current = null;
    }
  });
  const { focusSignal } = props;
  useEffect(() => {
    if (focusSignal > 0 && currentKey) moveTo(currentKey);
    // Only when the board asks, not whenever the current row changes.
  }, [focusSignal]);
  const onRowKey = (e: React.KeyboardEvent, r: Row, disabled: string | null) => {
    const i = rowKeys.indexOf(r.key);
    const go = (j: number) => {
      e.preventDefault();
      moveTo(rowKeys[Math.max(0, Math.min(rowKeys.length - 1, j))]);
    };
    if (e.key === 'ArrowDown') go(i + 1);
    else if (e.key === 'ArrowUp') go(i - 1);
    else if (e.key === 'Home') go(0);
    else if (e.key === 'End') go(rowKeys.length - 1);
    else if (e.key === 'PageDown') go(i + 10);
    else if (e.key === 'PageUp') go(i - 10);
    else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      if (disabled) props.onBlocked(r, disabled);
      else props.onClick(r, true);
    }
  };

  // Report where every row is (also rows scrolled out of view), so the board can draw lines to them.
  const { side, onLayout, anchor } = props;
  useEffect(() => {
    const viewport = parent.current;
    if (!viewport) return;
    const report = () => {
      const a = anchor.current?.getBoundingClientRect();
      const v = viewport.getBoundingClientRect();
      if (!a) return;
      const top = v.top - a.top;
      const ys = new Map<string, number>();
      items.forEach((it, i) => {
        if (it.kind === 'row') ys.set(it.row.key, top + i * ROW_HEIGHT + ROW_HEIGHT / 2 - viewport.scrollTop);
      });
      onLayout(side, { ys, top, bottom: top + v.height });
    };
    report();
    viewport.addEventListener('scroll', report, { passive: true });
    const resize = new ResizeObserver(report);
    resize.observe(viewport);
    return () => {
      viewport.removeEventListener('scroll', report);
      resize.disconnect();
    };
  }, [items, side, onLayout, anchor]);

  const linkState = (r: Row) => {
    const i = props.links.findIndex((l) => (props.side === 'source' ? l.from : l.to) === r.key);
    return i < 0 ? null : props.states[i];
  };

  return (
    <Paper withBorder p="xs" style={{ flex: 1, minWidth: 0 }}>
      <Stack gap={6}>
        <Text fw={600}>{props.title}</Text>
        <TextInput
          size="xs"
          placeholder="Search name or path"
          leftSection={<IconSearch size={14} />}
          value={query}
          onChange={(e) => setQuery(e.currentTarget.value)}
          aria-label={`Search ${props.title}`}
        />
        <Chip.Group multiple={false} value={filter} onChange={(v) => props.onFilter(v as Filter)}>
          <Group gap={4}>
            {(['all', 'required', 'unmapped', 'mapped'] as Filter[]).map((f) => (
              <Chip key={f} value={f} size="xs" variant="outline">
                {f[0].toUpperCase() + f.slice(1)}
              </Chip>
            ))}
            {hasProblems && (
              <Chip value="problems" size="xs" variant="outline" color={meaning.broken}>
                Problems
              </Chip>
            )}
          </Group>
        </Chip.Group>
        <ScrollArea h={shown.length === 0 ? 0 : TREE_HEIGHT} viewportRef={parent} type="auto">
          <div role="tree" aria-label={props.title} style={{ height: virtual.getTotalSize(), position: 'relative' }}>
            {virtual.getVirtualItems().map((v) => {
              const item = items[v.index];
              const style = { position: 'absolute' as const, top: 0, left: 0, right: 0, height: ROW_HEIGHT, transform: `translateY(${v.start}px)` };
              if (item.kind === 'group') {
                return (
                  <Text key={v.key} style={style} size="xs" fw={600} c="dimmed" pt={10} tt="uppercase">
                    {item.label}
                  </Text>
                );
              }
              const r = item.row;
              const disabled = props.isDisabled(r);
              const state = linkState(r);
              const dotColor = state ? (state.kind === 'ok' ? 'teal' : 'red') : null;
              const dot = (
                <Box
                  aria-hidden
                  style={{
                    width: 9,
                    height: 9,
                    flexShrink: 0,
                    borderRadius: '50%',
                    marginLeft: props.side === 'source' ? 'auto' : 0,
                    // At least 3:1 on white (WCAG 1.4.11): teal.7 / red.7 when linked, gray.6 when free.
                    border: `2px solid var(--mantine-color-${dotColor ?? 'gray'}-${dotColor ? 7 : 6})`,
                    background: dotColor ? `var(--mantine-color-${dotColor}-7)` : 'transparent',
                  }}
                />
              );
              const isSelected = props.selected === r.key;
              const amber = props.unmapped?.has(r.key);
              return (
                <Tooltip key={v.key} label={disabled ?? ''} disabled={!disabled} withArrow position="left" openDelay={200}>
                  <Box
                    id={`row-${props.side}-${r.key}`}
                    role="treeitem"
                    aria-level={r.depth + 1}
                    aria-selected={isSelected}
                    aria-disabled={!!disabled}
                    tabIndex={r.key === currentKey ? 0 : -1}
                    className="mantine-focus-auto"
                    style={{
                      ...style,
                      display: 'flex',
                      alignItems: 'center',
                      gap: 8,
                      paddingLeft: 6 + r.depth * 18,
                      paddingRight: 6,
                      cursor: disabled ? 'not-allowed' : 'pointer',
                      borderRadius: 4,
                      background: isSelected
                        ? 'var(--mantine-color-teal-light)'
                        : amber
                          ? 'var(--mantine-color-yellow-light)'
                          : undefined,
                      outline: isSelected ? '2px solid var(--mantine-color-teal-7)' : undefined,
                    }}
                    onClick={() => {
                      setCurrent(r.key);
                      if (!disabled) props.onClick(r, false);
                    }}
                    onKeyDown={(e) => onRowKey(e, r, disabled)}
                    onFocus={() => setCurrent(r.key)}
                  >
                    {/* Only the content fades on a row that can't be used; the focus ring around it stays at full strength. */}
                    <Box
                      style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 0, opacity: disabled && !r.missing ? 0.4 : 1 }}
                    >
                      {props.side === 'target' && dot}
                      {r.depth > 0 && (
                        <Text size="xs" c="dimmed" aria-hidden>
                          └
                        </Text>
                      )}
                      <Text
                        size="sm"
                        ff="monospace"
                        truncate
                        c={r.missing ? statusText.broken : undefined}
                        td={r.missing ? 'line-through' : undefined}
                        style={{ flexShrink: 1, minWidth: 0 }}
                      >
                        {r.label}
                        {r.required && (
                          <Text span c={statusText.broken} aria-label="required">
                            {' *'}
                          </Text>
                        )}
                      </Text>
                      <Text size="xs" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
                        {r.missing ? 'no longer exists' : r.freeForm ? 'free-form' : r.type ? TYPE_LABELS[r.type] ?? r.type : r.added ? 'added' : ''}
                        {r.constant ? ' · constant' : ''}
                      </Text>
                      {amber && <IconAlertTriangle size={14} color={statusText.warning} aria-label="required, not mapped" />}
                      {state && state.kind !== 'ok' && (
                        <Tooltip label={state.reason} withArrow>
                          <IconAlertTriangle size={14} color={statusText.broken} aria-label={state.reason} />
                        </Tooltip>
                      )}
                      {r.added && props.onRemoveRow && (
                        <ActionIcon
                          size="xs"
                          variant="subtle"
                          color="gray"
                          ml="auto"
                          aria-label={`Remove header ${r.label}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            props.onRemoveRow?.(r);
                          }}
                        >
                          <IconX size={12} />
                        </ActionIcon>
                      )}
                      {props.side === 'source' && dot}
                    </Box>
                  </Box>
                </Tooltip>
              );
            })}
          </div>
        </ScrollArea>
        {shown.length === 0 && (
          <Text size="sm" c="dimmed">
            {props.rows.length === 0 ? props.emptyText : 'No field matches.'}
          </Text>
        )}
        {props.footer}
      </Stack>
    </Paper>
  );
}

/**
 * Adds a header the contract doesn't declare, such as a correlation id (D-27); it is then linked like any other.
 * Headers with the same value on every call belong on the connection under Configuration.
 */
function AddHeader(props: { onAdd: (name: string) => Promise<void> }) {
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const add = async () => {
    if (!name.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await props.onAdd(name.trim());
      setName('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Group gap="xs" align="flex-start" wrap="nowrap">
      <TextInput
        size="xs"
        style={{ flex: 1 }}
        placeholder="Header name, e.g. X-Correlation-Id"
        aria-label="Header name"
        value={name}
        error={error}
        onChange={(e) => setName(e.currentTarget.value)}
        onKeyDown={(e) => e.key === 'Enter' && add()}
        styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
      />
      <Button size="xs" variant="light" onClick={add} loading={busy} disabled={!name.trim()}>
        Add header
      </Button>
    </Group>
  );
}

/**
 * The lines between the trees: a curve from each linked source row to its target row, teal when valid, red when it
 * can't be published (dashed when an end no longer exists). A row scrolled out of view gets its end on the panel's
 * edge, faded. Links whose field is filtered out are not drawn.
 */
function Lines(props: {
  links: Link[];
  states: LinkState[];
  source?: Layout;
  target?: Layout;
  selected: string | null;
  sources: Row[];
  targets: Row[];
  onRemove: (l: Link) => void;
}) {
  const { source, target } = props;
  const [hovered, setHovered] = useState<Link | null>(null);
  const [focused, setFocused] = useState<Link | null>(null);
  if (!source || !target) return null;
  const clamp = (y: number, l: Layout) => Math.min(Math.max(y, l.top), l.bottom);
  const inside = (y: number, l: Layout) => y >= l.top && y <= l.bottom;
  const label = (rows: Row[], key: string) => rows.find((r) => r.key === key)?.label ?? key;
  const drawn = props.links.flatMap((l, i) => {
    const ys = source.ys.get(l.from);
    const yt = target.ys.get(l.to);
    if (ys === undefined || yt === undefined) return [];
    const y1 = clamp(ys, source);
    const y2 = clamp(yt, target);
    return [{ l, state: props.states[i], y1, y2, visible: inside(ys, source) && inside(yt, target) }];
  });
  return (
    <>
      <svg width={GUTTER} height="100%" style={{ position: 'absolute', inset: 0, overflow: 'visible', pointerEvents: 'none' }} aria-hidden>
        {drawn.map(({ l, state, y1, y2, visible }) => {
          const d = `M -6 ${y1} C ${GUTTER / 2} ${y1}, ${GUTTER / 2} ${y2}, ${GUTTER + 6} ${y2}`;
          // teal.8 (3.95:1) and red.6 (3.3:1) keep the lines at 3:1 on white (WCAG 1.4.11); teal.6 reached 2.55:1.
          const color = state.kind === 'ok' ? 'var(--mantine-color-teal-8)' : 'var(--mantine-color-red-6)';
          return (
            <g key={`${l.from}->${l.to}`} onMouseEnter={() => setHovered(l)} onMouseLeave={() => setHovered(null)}>
              {/* A wide invisible stroke, so the line is easy to point at. */}
              <path d={d} fill="none" stroke="transparent" strokeWidth={12} style={{ pointerEvents: visible ? 'stroke' : 'none' }} />
              <path
                d={d}
                fill="none"
                stroke={color}
                strokeWidth={props.selected === l.from || hovered === l || focused === l ? 3 : 1.75}
                strokeDasharray={state.kind === 'broken' ? '5 4' : undefined}
                opacity={visible ? 1 : 0.3}
              />
            </g>
          );
        })}
      </svg>
      {/* An x on every line in view removes that link. It sits near the target end (t = 0.8 on the curve): a target
          field has one link at most, so two x's never land on the same spot, as they would where lines cross.
          It is shown only while its line is pointed at or the x has keyboard focus, so the gutter stays clear; it
          stays in the tab order, and the link list offers the same removal. */}
      {drawn
        .filter((x) => x.visible)
        .map(({ l, y1, y2 }) => {
          const t = 0.8;
          const at = (a: number, b: number, c: number, d: number) =>
            (1 - t) ** 3 * a + 3 * (1 - t) ** 2 * t * b + 3 * (1 - t) * t ** 2 * c + t ** 3 * d;
          const x = at(-6, GUTTER / 2, GUTTER / 2, GUTTER + 6);
          const y = at(y1, y1, y2, y2);
          return (
            <Tooltip key={`x-${l.from}->${l.to}`} label={`Remove ${label(props.sources, l.from)} → ${label(props.targets, l.to)}`} withArrow>
              <ActionIcon
                size={18}
                radius="xl"
                variant={hovered === l ? 'filled' : 'default'}
                color={meaning.broken}
                aria-label={`Remove link ${label(props.sources, l.from)} to ${label(props.targets, l.to)}`}
                onMouseEnter={() => setHovered(l)}
                onMouseLeave={() => setHovered(null)}
                onFocus={() => setFocused(l)}
                onBlur={() => setFocused(null)}
                onClick={() => {
                  setHovered(null);
                  setFocused(null);
                  props.onRemove(l);
                }}
                style={{
                  position: 'absolute',
                  left: x - 9,
                  top: y - 9,
                  opacity: hovered === l || focused === l ? 1 : 0,
                  transition: 'opacity 120ms',
                }}
              >
                <IconX size={12} />
              </ActionIcon>
            </Tooltip>
          );
        })}
    </>
  );
}


/**
 * The links as text, for keyboard and screen-reader use and for reading long paths. Folded by default, since the lines
 * show the same; links that can't be published are always listed.
 */
function LinkList(props: { links: Link[]; states: LinkState[]; sources: Row[]; targets: Row[]; onRemove: (l: Link) => void }) {
  const [open, setOpen] = useState(false);
  if (props.links.length === 0) return null;
  const label = (rows: Row[], key: string) => rows.find((r) => r.key === key)?.rule ?? key;
  const listed = props.links.map((l, i) => ({ l, s: props.states[i] })).filter(({ s }) => open || s.kind !== 'ok');
  return (
    <Paper withBorder p="xs">
      <Group gap="xs" mb={listed.length > 0 ? 4 : 0}>
        <Text fw={600} size="sm">
          Links on this tab ({props.links.length})
        </Text>
        <Button size="compact-xs" variant="subtle" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          {open ? 'Hide the list' : 'Show as a list'}
        </Button>
      </Group>
      <Stack gap={2}>
        {listed.map(({ l, s }) => {
          const color = s.kind === 'ok' ? 'teal.9' : statusText.broken;
          return (
            <Group key={`${l.from}->${l.to}`} gap="xs" wrap="nowrap">
              <Text size="sm" ff="monospace" c={color}>
                {s.kind !== 'ok' && '⚠ '}
                {label(props.sources, l.from)} → {label(props.targets, l.to)}
              </Text>
              {s.kind !== 'ok' && (
                <Text size="xs" c={statusText.broken}>
                  {s.reason}
                </Text>
              )}
              {s.kind === 'broken' ? (
                <Button size="compact-xs" variant="light" color={meaning.broken} onClick={() => props.onRemove(l)}>
                  Delete mapping to deleted field
                </Button>
              ) : (
                <Tooltip label="Remove this link" withArrow>
                  <ActionIcon size="sm" variant="subtle" color="gray" onClick={() => props.onRemove(l)} aria-label="Remove link">
                    <IconX size={14} />
                  </ActionIcon>
                </Tooltip>
              )}
            </Group>
          );
        })}
      </Stack>
    </Paper>
  );
}

function TestDrawer(props: { slug: string; input: PayloadDefinition | null; method: string; onResult: (r: TestResult) => void }) {
  const [text, setText] = useState(() => JSON.stringify(exampleInput(props.input), null, 2));
  const [real, setReal] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<TestResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async () => {
    setConfirming(false);
    setError(null);
    let input: unknown;
    try {
      input = JSON.parse(text);
    } catch (e) {
      setError(`The sample is not valid JSON: ${(e as Error).message}`);
      return;
    }
    setBusy(true);
    try {
      const r = await api.test(props.slug, input, real);
      setResult(r);
      props.onResult(r);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Paper withBorder p="xs" w={380} style={{ flexShrink: 0 }}>
      <Stack gap="xs">
        <Text fw={600}>Test</Text>
        <Textarea
          label="Sample input"
          autosize
          minRows={5}
          maxRows={12}
          ff="monospace"
          value={text}
          onChange={(e) => setText(e.currentTarget.value)}
        />
        <Switch
          label="Call the real system"
          description={real ? 'Sends a real request' : 'Uses a mock answer built from the contract'}
          checked={real}
          onChange={(e) => setReal(e.currentTarget.checked)}
        />
        <Button onClick={() => (real && props.method !== 'GET' ? setConfirming(true) : run())} loading={busy}>
          Run test
        </Button>
        {error && <ErrorBox text={error} />}
        {result && <CallSteps result={result} passed="Test passed" />}
      </Stack>
      <Modal opened={confirming} onClose={() => setConfirming(false)} title="Send a real request?">
        <Stack>
          <Text>This sends a real {props.method} request and may create or change data in the external system.</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button color={meaning.broken} onClick={run}>
              Send it
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Paper>
  );
}

function ErrorBox({ text }: { text: string }) {
  return (
    <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
      {text}
    </Alert>
  );
}
