import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { Select, SelectOption } from './Select';

function Example() {
  const [value, setValue] = useState('a');
  return <><label htmlFor="choice">貨主</label><Select id="choice" value={value} onValueChange={setValue}>
    <SelectOption value="a">Alpha</SelectOption>
    <SelectOption value="b" disabled>Beta</SelectOption>
    <SelectOption value="c">Charlie</SelectOption>
  </Select><button>其他位置</button></>;
}
it('selects with the keyboard, skips disabled options and retains trigger focus', async () => {
  const user = userEvent.setup();
  render(<Example />);
  const trigger = screen.getByRole('combobox', { name: '貨主' });
  await user.click(trigger);
  await user.keyboard('{ArrowDown}{Enter}');
  expect(trigger).toHaveValue('c');
  expect(trigger).toHaveFocus();
  expect(trigger).toHaveAttribute('aria-expanded', 'false');
  await user.click(trigger);
  await user.keyboard('{Home}{Enter}');
  expect(trigger).toHaveValue('a');
});
it('supports typeahead, Escape without committing and outside-click dismissal', async () => {
  const user = userEvent.setup();
  const parentKey = vi.fn();
  render(<div onKeyDown={parentKey}><Example /></div>);
  const trigger = screen.getByRole('combobox');
  await user.click(trigger);
  await user.keyboard('c{Escape}');
  expect(trigger).toHaveValue('a');
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  expect(parentKey.mock.calls.some(([event]) => event.key === 'Escape')).toBe(false);
  await user.click(trigger);
  await user.keyboard('c{Enter}');
  expect(trigger).toHaveValue('c');
  await user.click(trigger);
  await user.click(screen.getByRole('button', { name: '其他位置' }));
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
});
it('mouse selection commits once and Tab closes without trapping focus', async () => {
  const user = userEvent.setup();
  render(<Example />);
  const trigger = screen.getByRole('combobox');
  await user.click(trigger);
  await user.click(screen.getByRole('option', { name: 'Charlie' }));
  expect(trigger).toHaveValue('c');
  await user.click(trigger);
  await user.tab();
  expect(screen.getByRole('button', { name: '其他位置' })).toHaveFocus();
  expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
});
it('a disabled fieldset prevents changes even from an already open menu', async () => {
  const change = vi.fn();
  const view = (disabled: boolean) => <fieldset disabled={disabled}><Select aria-label="Choice" value="a" onValueChange={change}>
    <SelectOption value="a">Alpha</SelectOption><SelectOption value="c">Charlie</SelectOption>
  </Select></fieldset>;
  const { rerender } = render(view(false));
  fireEvent.click(screen.getByRole('combobox'));
  rerender(view(true));
  fireEvent.click(screen.getByRole('option', { name: 'Charlie' }));
  expect(change).not.toHaveBeenCalled();
});

it('selects the only enabled non-placeholder option when options arrive', () => {
  const change = vi.fn();
  const view = (loaded: boolean, value = '') => <Select value={value} autoSelectSingle onValueChange={change}>
    <SelectOption value="">請選擇</SelectOption>
    {loaded ? <SelectOption value="only">唯一選項</SelectOption> : null}
    <SelectOption value="disabled" disabled>不可選</SelectOption>
  </Select>;
  const { rerender } = render(view(false));
  expect(change).not.toHaveBeenCalled();
  rerender(view(true));
  expect(change).toHaveBeenCalledExactlyOnceWith('only');
  rerender(view(true, 'only'));
  expect(change).toHaveBeenCalledTimes(1);
});
it('does not auto-select filters, multiple options or overwrite an existing value', () => {
  const change = vi.fn();
  const { rerender } = render(<Select value="" onValueChange={change}>
    <SelectOption value="">全部</SelectOption><SelectOption value="a">Alpha</SelectOption>
  </Select>);
  expect(change).not.toHaveBeenCalled();
  rerender(<Select autoSelectSingle value="" onValueChange={change}>
    <SelectOption value="a">Alpha</SelectOption><SelectOption value="b">Beta</SelectOption>
  </Select>);
  expect(change).not.toHaveBeenCalled();
  rerender(<Select autoSelectSingle value="existing" onValueChange={change}>
    <SelectOption value="a">Alpha</SelectOption>
  </Select>);
  expect(change).not.toHaveBeenCalled();
});
it('auto-selection respects a disabled fieldset and resumes when unlocked', () => {
  const change = vi.fn();
  const view = (disabled: boolean) => <fieldset disabled={disabled}><Select autoSelectSingle value="" onValueChange={change}>
    <SelectOption value="">請選擇</SelectOption><SelectOption value="a">Alpha</SelectOption>
  </Select></fieldset>;
  const { rerender } = render(view(true));
  expect(change).not.toHaveBeenCalled();
  rerender(view(false));
  expect(change).toHaveBeenCalledExactlyOnceWith('a');
});
