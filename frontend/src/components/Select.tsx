import { Children, isValidElement, useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import styles from './Select.module.css';

interface OptionProps { value: string; children: ReactNode; disabled?: boolean }
export function SelectOption(_props: OptionProps) { return null; }

/** Controlled single-select. Focus stays on the trigger while navigating the listbox. */
export function Select({ id, value, onValueChange, children, disabled, className, autoSelectSingle = false, ...aria }: {
  id?: string; value: string; onValueChange: (value: string) => void; children: ReactNode;
  autoSelectSingle?: boolean;
  disabled?: boolean; className?: string | undefined; 'aria-label'?: string; 'aria-labelledby'?: string;
}) {
  const listId = useId();
  const root = useRef<HTMLSpanElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const search = useRef({ text: '', time: 0 });
  const options = Children.toArray(children).filter(isValidElement<OptionProps>).map(child => child.props);
  const selected = options.findIndex(option => option.value === value);
  const expanded = open && !disabled;
  const available = options.filter(option => option.value !== '' && !option.disabled);
  const soleValue = available.length === 1 ? available[0]!.value : null;
  useEffect(() => {
    if (autoSelectSingle && value === '' && soleValue !== null && !disabled
      && !trigger.current?.matches(':disabled')) onValueChange(soleValue);
  }); // 同時檢查父層 fieldset 的 disabled 狀態；它不一定反映在本元件 props。
  useEffect(() => {
    if (!expanded) return;
    const closeOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [expanded]);
  useEffect(() => {
    if (expanded) document.getElementById(`${listId}-${active}`)?.scrollIntoView?.({ block: 'nearest' });
  }, [active, expanded, listId]);
  function choose(index: number) {
    const option = options[index];
    if (!option || option.disabled || trigger.current?.matches(':disabled')) return;
    onValueChange(option.value);
    setOpen(false);
    trigger.current?.focus();
  }
  function move(start: number, direction: number) {
    for (let n = 0; n < options.length; n++) {
      const index = (start + direction * n + options.length) % options.length;
      if (!options[index]?.disabled) { setActive(index); return; }
    }
  }
  return <span ref={root} className={styles.root} onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
  }}>
    <button {...aria} ref={trigger} id={id} type="button" role="combobox" value={value}
      className={`${className ?? ''} ${styles.trigger}`} disabled={disabled}
      aria-expanded={expanded} aria-haspopup="listbox" aria-controls={expanded ? listId : undefined}
      aria-activedescendant={expanded && options[active] ? `${listId}-${active}` : undefined}
      onClick={() => { search.current = { text: '', time: 0 }; setActive(Math.max(0, selected)); setOpen(!expanded); }}
      onKeyDown={event => {
        if (event.currentTarget.matches(':disabled')) return;
        if (event.key === 'Escape' && expanded) {
          event.preventDefault(); event.stopPropagation(); setOpen(false); return;
        }
        if (event.key === 'Tab') { setOpen(false); return; }
        if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
          event.preventDefault(); setOpen(true);
          const direction = event.key === 'ArrowUp' || event.key === 'End' ? -1 : 1;
          move(event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
            : expanded ? active + direction : Math.max(0, selected), direction);
        } else if (expanded && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault(); choose(active);
        } else if (event.key.length === 1 && event.key !== ' ' && !event.ctrlKey && !event.metaKey && !event.altKey) {
          event.preventDefault();
          const now = Date.now();
          search.current = { text: (now - search.current.time < 700 ? search.current.text : '') + event.key, time: now };
          const index = options.findIndex(option => !option.disabled
            && Children.toArray(option.children).join('').toLocaleLowerCase().startsWith(search.current.text.toLocaleLowerCase()));
          if (index >= 0) { setActive(index); setOpen(true); }
        }
      }}>
      <span>{options[selected]?.children ?? '請選擇'}</span><span aria-hidden="true" className={styles.chevron}>⌄</span>
    </button>
    {expanded ? <span id={listId} role="listbox" aria-label={aria['aria-label'] ?? '選項'} className={styles.options}>
      {options.map((option, index) => <span key={option.value} id={`${listId}-${index}`} role="option"
        aria-selected={option.value === value} aria-disabled={option.disabled || undefined}
        data-value={option.value} className={`${styles.option} ${active === index ? styles.active : ''}`}
        onPointerDown={event => event.preventDefault()}
        onClick={event => { event.preventDefault(); choose(index); }}>
        <span>{option.children}</span><span aria-hidden="true">{option.value === value ? '✓' : ''}</span>
      </span>)}
      {!options.length ? <span className={styles.option}>沒有可用選項</span> : null}
    </span> : null}
  </span>;
}
