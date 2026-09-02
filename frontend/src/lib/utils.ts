import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** shadcn/ui 의 관례. 조건부 클래스와 Tailwind 충돌 해소를 함께 처리한다. */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
