import type { InputHTMLAttributes } from 'react';
import { cn } from '@/lib/utils';

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        'h-9 w-full rounded-md border border-rule-strong bg-paper px-3 text-sm text-ink',
        'placeholder:text-muted focus-visible:outline-2 focus-visible:outline-offset-0',
        'focus-visible:outline-clay disabled:opacity-50',
        className,
      )}
      {...props}
    />
  );
}
