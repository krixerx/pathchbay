/**
 * The Patchbay mark (D-25): jacks on both sides, two cables between them. Filled jacks are connected, hollow ones
 * free, as the dots on the mapping board. The same drawing is `public/favicon.svg` and `docs/images/patchbay-logo.svg`.
 */
export function Logo(props: { size?: number }) {
  const size = props.size ?? 28;
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="6" fill="var(--mantine-color-teal-8)" />
      <g fill="none" stroke="#fff" strokeWidth="2.4" strokeLinecap="round">
        <path d="M8 9 C16 9 16 23 24 23" />
        <path d="M8 16 C16 16 16 9 24 9" />
      </g>
      <g fill="#fff">
        <circle cx="8" cy="9" r="2.6" />
        <circle cx="8" cy="16" r="2.6" />
        <circle cx="24" cy="9" r="2.6" />
        <circle cx="24" cy="23" r="2.6" />
      </g>
      <g fill="none" stroke="#fff" strokeWidth="1.6" opacity="0.75">
        <circle cx="8" cy="23" r="2.2" />
        <circle cx="24" cy="16" r="2.2" />
      </g>
    </svg>
  );
}
