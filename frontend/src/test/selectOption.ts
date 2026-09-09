import { screen } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';

export async function selectOption(user: UserEvent, trigger: HTMLElement, value: string) {
  await user.click(trigger);
  const option = screen.getAllByRole('option').find(candidate => candidate.getAttribute('data-value') === value);
  if (!option) throw new Error(`Option not found: ${value}`);
  await user.click(option);
}
