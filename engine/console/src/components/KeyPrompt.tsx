import { useEffect, useState } from 'react';
import { Button, Group, Modal, PasswordInput, Stack, Text } from '@mantine/core';
import { setInboundKey, UNAUTHORIZED_EVENT } from '../api';

/** Asks for the engine's INBOUND_API_KEY when a call is refused with 401, then reloads with the key. */
export function KeyPrompt() {
  const [open, setOpen] = useState(false);
  const [key, setKey] = useState('');

  useEffect(() => {
    const onUnauthorized = () => setOpen(true);
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, []);

  const save = () => {
    setInboundKey(key.trim());
    window.location.reload();
  };

  return (
    <Modal opened={open} onClose={() => setOpen(false)} title="Key needed" closeOnClickOutside={false}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (key.trim()) save();
        }}
      >
        <Stack>
          <Text size="sm">
            This engine is protected with an API key (the <code>INBOUND_API_KEY</code> variable it was started with). Enter
            it to continue. It is kept only while this browser tab is open.
          </Text>
          <PasswordInput label="API key" value={key} onChange={(e) => setKey(e.currentTarget.value)} data-autofocus />
          <Group justify="flex-end">
            <Button type="submit" disabled={!key.trim()}>
              Continue
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
