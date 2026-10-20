import { createTheme } from '@mantine/core';

// Tokens from DESIGN.md (D-16): IBM Plex, one teal accent, small radius, 16px body.
export const theme = createTheme({
  primaryColor: 'teal',
  primaryShade: 8,
  fontFamily: '"IBM Plex Sans", system-ui, sans-serif',
  fontFamilyMonospace: '"IBM Plex Mono", ui-monospace, monospace',
  headings: { fontFamily: '"IBM Plex Sans", system-ui, sans-serif', fontWeight: '600' },
  defaultRadius: 'xs',
  fontSizes: { xs: '13px', sm: '14px', md: '16px', lg: '18px', xl: '20px' },
  spacing: { xs: '4px', sm: '8px', md: '16px', lg: '24px', xl: '32px' },
  focusRing: 'auto',
});

/** Fixed meanings (DESIGN.md "Color meanings"). */
export const meaning = {
  accent: 'teal',
  warning: 'yellow',
  broken: 'red',
  passed: 'green',
  neutral: 'gray',
} as const;
