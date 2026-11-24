import type { CSSVariablesResolver } from '@mantine/core';
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

/**
 * Secondary text: hints under labels, type names, placeholders (D-17, WCAG AA). Mantine's defaults, gray.6 (#868e96)
 * for dimmed text and gray.5 (#adb5bd) for placeholders, reach only 3.3:1 and 2.1:1 on white. #62696f gives at least
 * 5:1 on white and on the light gray, teal, yellow and red backgrounds the console puts text on, and stays clearly
 * lighter than body text.
 */
export const SECONDARY_TEXT = '#62696f';

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {
    '--mantine-color-dimmed': SECONDARY_TEXT,
    '--mantine-color-placeholder': SECONDARY_TEXT,
  },
  dark: {},
});

/** Fixed meanings (DESIGN.md "Color meanings"). */
export const meaning = {
  accent: 'teal',
  warning: 'yellow',
  broken: 'red',
  passed: 'green',
  neutral: 'gray',
} as const;
