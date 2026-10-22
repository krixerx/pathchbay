import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Chip,
  Code,
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
} from "@mantine/core";
import { useMediaQuery } from "@mantine/hooks";
import { useVirtualizer } from "@tanstack/react-virtual";
import {
  IconAlertTriangle,
  IconCheck,
  IconSearch,
  IconX,
} from "@tabler/icons-react";
import { api } from "../../api";
import type {
  ContractOperation,
  IntegrationDraft,
  PayloadDefinition,
  TestResult,
} from "../../model";
import { TYPE_LABELS } from "../../model";
import type { Filter, Link, LinkState, Row, Tab } from "../../board";
import {
  addLink,
  canLink,
  exampleInput,
  filterRows,
  linkStates,
  payloadRows,
  requestRows,
  requiredUnmapped,
  responseRows,
} from "../../board";
import { meaning } from "../../theme";

const ROW_HEIGHT = 34;
const GUTTER = 120;

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
}) {
  const { draft, tab, testOpen } = props;
  const wide = useMediaQuery("(min-width: 1024px)");
  const [data, setData] = useState<BoardData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [links, setLinks] = useState<Record<Tab, Link[]>>({
    request: (draft.request?.links as Link[]) ?? [],
    response: (draft.response?.links as Link[]) ?? [],
  });
  const [selected, setSelected] = useState<string | null>(null);
  const [saved, setSaved] = useState<"idle" | "saving" | "saved">("idle");
  const [lastTest, setLastTest] = useState<{ ok: boolean; at: string } | null>(
    null,
  );
  const gutter = useRef<HTMLDivElement>(null);
  const [layouts, setLayouts] = useState<{ source?: Layout; target?: Layout }>(
    {},
  );
  const onLayout = useCallback(
    (side: "source" | "target", l: Layout) =>
      setLayouts((old) => ({ ...old, [side]: l })),
    [],
  );

  useEffect(() => {
    api.board(draft.id).then(setData, (e: Error) => setError(e.message));
  }, [draft.id]);

  // Esc cancels a pending source selection (D-17).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setSelected(null);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const sides = useMemo(() => {
    if (!data) return null;
    return tab === "request"
      ? {
          sources: payloadRows(data.input),
          targets: requestRows(data.operation),
          left: "Your Input",
          right: "API request",
        }
      : {
          sources: responseRows(data.operation),
          targets: payloadRows(data.output),
          left: "API response",
          right: "Your Output",
        };
  }, [data, tab]);

  const current = links[tab];
  const states = useMemo(
    () => (sides ? linkStates(current, sides.sources, sides.targets) : []),
    [current, sides],
  );
  const unmapped = useMemo(
    () => (sides ? requiredUnmapped(sides.targets, current) : []),
    [sides, current],
  );
  const redCount = states.filter((s) => s.kind !== "ok").length;
  const otherTab: Tab = tab === "request" ? "response" : "request";

  // Autosave on every change (D-1).
  const save = useCallback(
    async (next: Record<Tab, Link[]>) => {
      setLinks(next);
      setSaved("saving");
      try {
        props.onChange(
          await api.saveLinks(draft.id, next.request, next.response),
        );
        setSaved("saved");
      } catch (e) {
        setError((e as Error).message);
        setSaved("idle");
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
  if (!data || !sides)
    return error ? (
      <ErrorBox text={error} />
    ) : (
      <Skeleton height={300} mt="lg" />
    );
  if (!data.input || !data.output || !data.operation) {
    return (
      <Text mt="lg">
        Pick the Input, the Output and the operation above to map fields.
      </Text>
    );
  }

  const sourceRow = selected
    ? (sides.sources.find((r) => r.key === selected) ?? null)
    : null;

  const clickSource = (r: Row) =>
    setSelected(selected === r.key ? null : r.key);
  const clickTarget = (r: Row) => {
    if (!sourceRow) return;
    if (canLink(sourceRow, r, current, sides.sources, sides.targets)) return;
    save({ ...links, [tab]: addLink(current, sourceRow.key, r.key) });
    setSelected(null);
  };
  const remove = (l: Link) =>
    save({ ...links, [tab]: current.filter((x) => x !== l) });

  return (
    <Stack gap="sm" mt="md">
      <StatusBar
        unmapped={unmapped.length}
        red={redCount}
        other={{
          tab: otherTab,
          red: linkStates(
            links[otherTab],
            ...otherSides(data, otherTab),
          ).filter((s) => s.kind !== "ok").length,
        }}
        lastTest={lastTest}
        saved={saved}
        onShowUnmapped={() =>
          document
            .getElementById(`row-target-${unmapped[0]?.key}`)
            ?.scrollIntoView({ block: "center" })
        }
        onSwitch={() => props.onTab(otherTab)}
      />

      <SegmentedControl
        value={tab}
        onChange={(v) => {
          props.onTab(v as Tab);
          setSelected(null);
        }}
        data={[
          { value: "request", label: "Input → API request" },
          { value: "response", label: "API response → Output" },
        ]}
        style={{ alignSelf: "flex-start" }}
      />

      <Text size="sm" c="dimmed" aria-live="polite">
        {sourceRow
          ? `Now click the field on the right that “${sourceRow.label}” goes into. Esc cancels.`
          : "Click a field on the left, then the field on the right it goes into."}
      </Text>

      <Group align="stretch" gap={0} wrap="nowrap">
        <TreePanel
          title={sides.left}
          rows={sides.sources}
          side="source"
          links={current}
          states={states}
          partners={sides.targets}
          selected={selected}
          onClick={clickSource}
          isDisabled={() => null}
          anchor={gutter}
          onLayout={onLayout}
        />
        <div
          ref={gutter}
          style={{ width: GUTTER, flexShrink: 0, position: "relative" }}
        >
          <Lines
            links={current}
            states={states}
            source={layouts.source}
            target={layouts.target}
            selected={selected}
            sources={sides.sources}
            targets={sides.targets}
            onRemove={remove}
          />
        </div>
        <TreePanel
          title={sides.right}
          rows={sides.targets}
          side="target"
          links={current}
          states={states}
          partners={sides.sources}
          selected={null}
          onClick={clickTarget}
          isDisabled={(r) =>
            sourceRow
              ? canLink(sourceRow, r, current, sides.sources, sides.targets)
              : null
          }
          unmapped={new Set(unmapped.map((r) => r.key))}
          anchor={gutter}
          onLayout={onLayout}
        />
        {testOpen && (
          <TestDrawer
            slug={draft.id}
            input={data.input}
            method={data.operation.method}
            onResult={(r) =>
              setLastTest({
                ok: r.stage === "done",
                at: new Date().toLocaleTimeString([], {
                  hour: "2-digit",
                  minute: "2-digit",
                }),
              })
            }
          />
        )}
      </Group>

      <LinkList
        links={current}
        states={states}
        sources={sides.sources}
        targets={sides.targets}
        onRemove={remove}
      />
      {error && <ErrorBox text={error} />}
    </Stack>
  );
}

function otherSides(data: BoardData, tab: Tab): [Row[], Row[]] {
  return tab === "request"
    ? [payloadRows(data.input), requestRows(data.operation)]
    : [responseRows(data.operation), payloadRows(data.output)];
}

function StatusBar(props: {
  unmapped: number;
  red: number;
  other: { tab: Tab; red: number };
  lastTest: { ok: boolean; at: string } | null;
  saved: "idle" | "saving" | "saved";
  onShowUnmapped: () => void;
  onSwitch: () => void;
}) {
  return (
    <Paper withBorder p="xs" role="status">
      <Group gap="lg">
        {props.unmapped > 0 ? (
          <UnstyledButton onClick={props.onShowUnmapped}>
            <Group gap={4}>
              <IconAlertTriangle
                size={16}
                color="var(--mantine-color-yellow-7)"
              />
              <Text size="sm" c="yellow.8">
                {props.unmapped} required field{props.unmapped === 1 ? "" : "s"}{" "}
                unmapped ▸
              </Text>
            </Group>
          </UnstyledButton>
        ) : (
          <Text size="sm" c="dimmed">
            All required fields mapped
          </Text>
        )}
        {props.red > 0 && (
          <Group gap={4}>
            <IconX size={16} color="var(--mantine-color-red-7)" />
            <Text size="sm" c="red.7">
              {props.red} link{props.red === 1 ? "" : "s"} can't be published
            </Text>
          </Group>
        )}
        {props.other.red > 0 && (
          <UnstyledButton onClick={props.onSwitch}>
            <Text size="sm" c="red.7">
              {props.other.red} problem{props.other.red === 1 ? "" : "s"} on the
              other tab ▸
            </Text>
          </UnstyledButton>
        )}
        {props.lastTest && (
          <Group gap={4}>
            {props.lastTest.ok ? (
              <IconCheck size={16} color="var(--mantine-color-green-7)" />
            ) : (
              <IconX size={16} color="var(--mantine-color-red-7)" />
            )}
            <Text size="sm" c={props.lastTest.ok ? "green.8" : "red.7"}>
              Last test {props.lastTest.ok ? "passed" : "failed"}{" "}
              {props.lastTest.at}
            </Text>
          </Group>
        )}
        <Text size="sm" c="dimmed" ml="auto">
          {props.saved === "saving"
            ? "Saving…"
            : props.saved === "saved"
              ? "✓ Saved"
              : ""}
        </Text>
      </Group>
    </Paper>
  );
}

type Item = { kind: "group"; label: string } | { kind: "row"; row: Row };

function TreePanel(props: {
  title: string;
  rows: Row[];
  side: "source" | "target";
  links: Link[];
  states: LinkState[];
  partners: Row[];
  selected: string | null;
  onClick: (r: Row) => void;
  isDisabled: (r: Row) => string | null;
  unmapped?: Set<string>;
  anchor: React.RefObject<HTMLDivElement | null>;
  onLayout: (side: "source" | "target", l: Layout) => void;
}) {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<Filter>("all");
  const parent = useRef<HTMLDivElement>(null);
  const linkedKeys = useMemo(
    () =>
      new Set(
        props.links.map((l) => (props.side === "source" ? l.from : l.to)),
      ),
    [props.links, props.side],
  );
  const shown = useMemo(
    () => filterRows(props.rows, query, filter, linkedKeys),
    [props.rows, query, filter, linkedKeys],
  );
  const items = useMemo(() => {
    const out: Item[] = [];
    let group: string | undefined;
    for (const r of shown) {
      if (r.group && r.group !== group && r.group !== "Response")
        out.push({ kind: "group", label: r.group });
      group = r.group;
      out.push({ kind: "row", row: r });
    }
    return out;
  }, [shown]);
  const virtual = useVirtualizer({
    count: items.length,
    getScrollElement: () => parent.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 12,
  });

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
        if (it.kind === "row")
          ys.set(
            it.row.key,
            top + i * ROW_HEIGHT + ROW_HEIGHT / 2 - viewport.scrollTop,
          );
      });
      onLayout(side, { ys, top, bottom: top + v.height });
    };
    report();
    viewport.addEventListener("scroll", report, { passive: true });
    const resize = new ResizeObserver(report);
    resize.observe(viewport);
    return () => {
      viewport.removeEventListener("scroll", report);
      resize.disconnect();
    };
  }, [items, side, onLayout, anchor]);

  const linkState = (r: Row) => {
    const i = props.links.findIndex(
      (l) => (props.side === "source" ? l.from : l.to) === r.key,
    );
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
        <Chip.Group
          multiple={false}
          value={filter}
          onChange={(v) => setFilter(v as Filter)}
        >
          <Group gap={4}>
            {(["all", "required", "unmapped", "mapped"] as Filter[]).map(
              (f) => (
                <Chip key={f} value={f} size="xs" variant="outline">
                  {f[0].toUpperCase() + f.slice(1)}
                </Chip>
              ),
            )}
          </Group>
        </Chip.Group>
        <ScrollArea
          h={shown.length === 0 ? 0 : 460}
          viewportRef={parent}
          type="auto"
        >
          <div
            role="tree"
            aria-label={props.title}
            style={{ height: virtual.getTotalSize(), position: "relative" }}
          >
            {virtual.getVirtualItems().map((v) => {
              const item = items[v.index];
              const style = {
                position: "absolute" as const,
                top: 0,
                left: 0,
                right: 0,
                height: ROW_HEIGHT,
                transform: `translateY(${v.start}px)`,
              };
              if (item.kind === "group") {
                return (
                  <Text
                    key={v.key}
                    style={style}
                    size="xs"
                    fw={600}
                    c="dimmed"
                    pt={10}
                    tt="uppercase"
                  >
                    {item.label}
                  </Text>
                );
              }
              const r = item.row;
              const disabled = props.isDisabled(r);
              const state = linkState(r);
              const dotColor = state
                ? state.kind === "ok"
                  ? "teal"
                  : "red"
                : null;
              const dot = (
                <Box
                  aria-hidden
                  style={{
                    width: 9,
                    height: 9,
                    flexShrink: 0,
                    borderRadius: "50%",
                    marginLeft: props.side === "source" ? "auto" : 0,
                    border: `2px solid var(--mantine-color-${dotColor ?? "gray"}-${dotColor ? 7 : 4})`,
                    background: dotColor
                      ? `var(--mantine-color-${dotColor}-7)`
                      : "transparent",
                  }}
                />
              );
              const isSelected = props.selected === r.key;
              const amber = props.unmapped?.has(r.key);
              return (
                <Tooltip
                  key={v.key}
                  label={disabled ?? ""}
                  disabled={!disabled}
                  withArrow
                  position="left"
                  openDelay={200}
                >
                  <Box
                    id={`row-${props.side}-${r.key}`}
                    role="treeitem"
                    aria-level={r.depth + 1}
                    aria-selected={isSelected}
                    aria-disabled={!!disabled}
                    tabIndex={0}
                    style={{
                      ...style,
                      display: "flex",
                      alignItems: "center",
                      gap: 8,
                      paddingLeft: 6 + r.depth * 18,
                      paddingRight: 6,
                      cursor: disabled ? "not-allowed" : "pointer",
                      opacity: disabled ? 0.4 : 1,
                      borderRadius: 4,
                      background: isSelected
                        ? "var(--mantine-color-teal-light)"
                        : amber
                          ? "var(--mantine-color-yellow-light)"
                          : undefined,
                      outline: isSelected
                        ? "2px solid var(--mantine-color-teal-7)"
                        : undefined,
                    }}
                    onClick={() => !disabled && props.onClick(r)}
                    onKeyDown={(e) =>
                      e.key === "Enter" && !disabled && props.onClick(r)
                    }
                  >
                    {props.side === "target" && dot}
                    {r.depth > 0 && (
                      <Text size="xs" c="dimmed" aria-hidden>
                        └
                      </Text>
                    )}
                    <Text
                      size="sm"
                      ff="monospace"
                      truncate
                      style={{ flexShrink: 1, minWidth: 0 }}
                    >
                      {r.label}
                      {r.required && (
                        <Text span c="red.7" aria-label="required">
                          {" *"}
                        </Text>
                      )}
                    </Text>
                    <Text size="xs" c="dimmed" style={{ whiteSpace: "nowrap" }}>
                      {r.type ? (TYPE_LABELS[r.type] ?? r.type) : ""}
                      {r.constant ? " · constant" : ""}
                    </Text>
                    {amber && (
                      <IconAlertTriangle
                        size={14}
                        color="var(--mantine-color-yellow-7)"
                        aria-label="required, not mapped"
                      />
                    )}
                    {state && state.kind !== "ok" && (
                      <Tooltip label={state.reason} withArrow>
                        <IconAlertTriangle
                          size={14}
                          color="var(--mantine-color-red-7)"
                          aria-label={state.reason}
                        />
                      </Tooltip>
                    )}
                    {props.side === "source" && dot}
                  </Box>
                </Tooltip>
              );
            })}
          </div>
        </ScrollArea>
        {shown.length === 0 && (
          <Text size="sm" c="dimmed">
            {props.rows.length === 0
              ? "The contract doesn't describe this side, so its fields can't be shown or checked. Run a real test to see what comes back."
              : "No field matches."}
          </Text>
        )}
      </Stack>
    </Paper>
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
  if (!source || !target) return null;
  const clamp = (y: number, l: Layout) =>
    Math.min(Math.max(y, l.top), l.bottom);
  const inside = (y: number, l: Layout) => y >= l.top && y <= l.bottom;
  const label = (rows: Row[], key: string) =>
    rows.find((r) => r.key === key)?.label ?? key;
  const drawn = props.links.flatMap((l, i) => {
    const ys = source.ys.get(l.from);
    const yt = target.ys.get(l.to);
    if (ys === undefined || yt === undefined) return [];
    const y1 = clamp(ys, source);
    const y2 = clamp(yt, target);
    return [
      {
        l,
        state: props.states[i],
        y1,
        y2,
        visible: inside(ys, source) && inside(yt, target),
      },
    ];
  });
  return (
    <>
      <svg
        width={GUTTER}
        height="100%"
        style={{
          position: "absolute",
          inset: 0,
          overflow: "visible",
          pointerEvents: "none",
        }}
        aria-hidden
      >
        {drawn.map(({ l, state, y1, y2, visible }) => {
          const d = `M -6 ${y1} C ${GUTTER / 2} ${y1}, ${GUTTER / 2} ${y2}, ${GUTTER + 6} ${y2}`;
          const color =
            state.kind === "ok"
              ? "var(--mantine-color-teal-6)"
              : "var(--mantine-color-red-6)";
          return (
            <g
              key={`${l.from}->${l.to}`}
              onMouseEnter={() => setHovered(l)}
              onMouseLeave={() => setHovered(null)}
            >
              {/* A wide invisible stroke, so the line is easy to point at. */}
              <path
                d={d}
                fill="none"
                stroke="transparent"
                strokeWidth={12}
                style={{ pointerEvents: visible ? "stroke" : "none" }}
              />
              <path
                d={d}
                fill="none"
                stroke={color}
                strokeWidth={
                  props.selected === l.from || hovered === l ? 3 : 1.75
                }
                strokeDasharray={state.kind === "broken" ? "5 4" : undefined}
                opacity={visible ? 1 : 0.3}
              />
            </g>
          );
        })}
      </svg>
      {/* An x on every line in view removes that link. It sits near the target end (t = 0.8 on the curve): a target
          field has one link at most, so two x's never land on the same spot, as they would where lines cross. */}
      {drawn
        .filter((x) => x.visible)
        .map(({ l, y1, y2 }) => {
          const t = 0.8;
          const at = (a: number, b: number, c: number, d: number) =>
            (1 - t) ** 3 * a +
            3 * (1 - t) ** 2 * t * b +
            3 * (1 - t) * t ** 2 * c +
            t ** 3 * d;
          const x = at(-6, GUTTER / 2, GUTTER / 2, GUTTER + 6);
          const y = at(y1, y1, y2, y2);
          return (
            <Tooltip
              key={`x-${l.from}->${l.to}`}
              label={`Remove ${label(props.sources, l.from)} → ${label(props.targets, l.to)}`}
              withArrow
            >
              <ActionIcon
                size={18}
                radius="xl"
                variant={hovered === l ? "filled" : "default"}
                color={meaning.broken}
                aria-label={`Remove link ${label(props.sources, l.from)} to ${label(props.targets, l.to)}`}
                onMouseEnter={() => setHovered(l)}
                onMouseLeave={() => setHovered(null)}
                onClick={() => {
                  setHovered(null);
                  props.onRemove(l);
                }}
                style={{ position: "absolute", left: x - 9, top: y - 9 }}
              >
                <IconX size={12} />
              </ActionIcon>
            </Tooltip>
          );
        })}
    </>
  );
}

function LinkList(props: {
  links: Link[];
  states: LinkState[];
  sources: Row[];
  targets: Row[];
  onRemove: (l: Link) => void;
}) {
  if (props.links.length === 0) return null;
  const label = (rows: Row[], key: string) =>
    rows.find((r) => r.key === key)?.rule ?? key;
  return (
    <Paper withBorder p="xs">
      <Text fw={600} size="sm" mb={4}>
        Links on this tab
      </Text>
      <Stack gap={2}>
        {props.links.map((l, i) => {
          const s = props.states[i];
          const color = s.kind === "ok" ? "teal.8" : "red.7";
          return (
            <Group key={`${l.from}->${l.to}`} gap="xs" wrap="nowrap">
              <Text size="sm" ff="monospace" c={color}>
                {s.kind !== "ok" && "⚠ "}
                {label(props.sources, l.from)} → {label(props.targets, l.to)}
              </Text>
              {s.kind !== "ok" && (
                <Text size="xs" c="red.7">
                  {s.reason}
                </Text>
              )}
              {s.kind === "broken" ? (
                <Button
                  size="compact-xs"
                  variant="light"
                  color={meaning.broken}
                  onClick={() => props.onRemove(l)}
                >
                  Delete mapping to deleted field
                </Button>
              ) : (
                <Tooltip label="Remove this link" withArrow>
                  <ActionIcon
                    size="sm"
                    variant="subtle"
                    color="gray"
                    onClick={() => props.onRemove(l)}
                    aria-label="Remove link"
                  >
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

function TestDrawer(props: {
  slug: string;
  input: PayloadDefinition | null;
  method: string;
  onResult: (r: TestResult) => void;
}) {
  const [text, setText] = useState(() =>
    JSON.stringify(exampleInput(props.input), null, 2),
  );
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

  const ok = result?.stage === "done";

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
          description={
            real
              ? "Sends a real request"
              : "Uses a mock answer built from the contract"
          }
          checked={real}
          onChange={(e) => setReal(e.currentTarget.checked)}
        />
        <Button
          onClick={() =>
            real && props.method !== "GET" ? setConfirming(true) : run()
          }
          loading={busy}
        >
          Run test
        </Button>
        {error && <ErrorBox text={error} />}
        {result && (
          <Stack gap={6} aria-live="polite">
            <Group gap={4}>
              {ok ? (
                <IconCheck size={16} color="var(--mantine-color-green-7)" />
              ) : (
                <IconX size={16} color="var(--mantine-color-red-7)" />
              )}
              <Text size="sm" c={ok ? "green.8" : "red.7"} fw={500}>
                {ok ? "Test passed" : `Stopped at: ${result.stage}`}
              </Text>
              {result.mock && (
                <Badge variant="light" color="gray">
                  mock
                </Badge>
              )}
            </Group>
            {result.problems.length > 0 && (
              <Alert color={ok ? meaning.warning : meaning.broken} p="xs">
                {result.problems.map((p) => (
                  <Text key={p} size="xs">
                    {p}
                  </Text>
                ))}
              </Alert>
            )}
            {result.request && (
              <Block title="Request sent">
                {`${result.request.method} ${result.request.url}\n`}
                {Object.entries(result.request.headers)
                  .map(([k, v]) => `${k}: ${v}`)
                  .join("\n")}
                {result.request.body !== null &&
                result.request.body !== undefined
                  ? `\n\n${JSON.stringify(result.request.body, null, 2)}`
                  : ""}
              </Block>
            )}
            {result.response && (
              <Block title={`Answer ${result.response.status}`}>
                {JSON.stringify(result.response.body, null, 2) ?? "(empty)"}
              </Block>
            )}
            {result.output !== undefined && result.output !== null && (
              <Block title="Mapped to Output">
                {JSON.stringify(result.output, null, 2)}
              </Block>
            )}
          </Stack>
        )}
      </Stack>
      <Modal
        opened={confirming}
        onClose={() => setConfirming(false)}
        title="Send a real request?"
      >
        <Stack>
          <Text>
            This sends a real {props.method} request and may create or change
            data in the external system.
          </Text>
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

function Block(props: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <Text size="xs" fw={600} c="dimmed">
        {props.title}
      </Text>
      <Code
        block
        style={{
          maxHeight: 220,
          overflow: "auto",
          fontSize: 12,
          whiteSpace: "pre-wrap",
          wordBreak: "break-all",
        }}
      >
        {props.children}
      </Code>
    </div>
  );
}

function ErrorBox({ text }: { text: string }) {
  return (
    <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
      {text}
    </Alert>
  );
}
