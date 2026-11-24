import type { CSSVariablesResolver } from '@mantine/core';
import { createTheme } from '@mantine/core';

// Tokens from DESIGN.md (D-16): IBM Plex, one teal accent, small radius, 16px body.
export const theme = createTheme({
  primaryColor: 'teal',
  // Shade 9 (#087f5b): 5.0:1 on white, so links and white text on primary buttons meet WCAG AA. Shade 8 reached 3.95:1.
  primaryShade: 9,
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

/**
 * Status text and the icons beside it (D-17, WCAG AA). Mantine's amber, green and red at the shades the console used
 * (yellow.8, green.8, red.7) reached 2.5:1, 3.5:1 and 3.8:1 on white. These reach at least 4.9:1 on white and on the
 * light gray, yellow, red, green and teal backgrounds.
 */
export const statusText = {
  warning: '#9c5d00',
  broken: 'var(--mantine-color-red-9)',
  passed: '#247534',
} as const;

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {},
  light: {
    '--mantine-color-dimmed': SECONDARY_TEXT,
    '--mantine-color-placeholder': SECONDARY_TEXT,
    // The light teal behind teal text (active menu item, Live badges, selected row): teal.0 instead of Mantine's
    // teal.1-based tint, so teal.9 text on it reaches 4.7:1 instead of 4.3:1.
    '--mantine-color-teal-light': 'var(--mantine-color-teal-0)',
    '--mantine-primary-color-light': 'var(--mantine-color-teal-0)',
    // Text of Mantine's light variants (alert titles, light buttons): yellow.9 and green.9 fall below 4.5:1.
    '--mantine-color-yellow-light-color': statusText.warning,
    '--mantine-color-green-light-color': statusText.passed,
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
