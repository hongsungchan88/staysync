import { cva, type VariantProps } from 'class-variance-authority';
import type { ButtonHTMLAttributes } from 'react';
import { cn } from '@/lib/utils';

/**
 * shadcn/ui 의 구조를 따른다. 컴포넌트를 저장소에 두고 직접 고치는 방식이다.
 *
 * CLI 로 생성하지 않고 손으로 쓴 이유는 지금 필요한 것이 버튼과 입력 둘뿐이기
 * 때문이다. Radix 프리미티브는 쓰는 컴포넌트가 생길 때 그때 들인다.
 * 쓰지 않는 의존을 미리 넣지 않는다는 규칙이 여기에도 적용된다.
 */
const buttonVariants = cva(
  'inline-flex items-center justify-center rounded-md text-sm font-medium transition ' +
    'disabled:pointer-events-none disabled:opacity-50 focus-visible:outline-2 ' +
    'focus-visible:outline-offset-2 focus-visible:outline-clay',
  {
    variants: {
      variant: {
        primary: 'bg-clay text-paper hover:brightness-95',
        default: 'border border-rule-strong bg-paper text-body hover:bg-faint',
        ghost: 'text-body hover:bg-faint',
      },
      size: {
        default: 'h-9 px-4',
        sm: 'h-8 px-3 text-xs',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
);

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

export function Button({ className, variant, size, ...props }: ButtonProps) {
  return <button className={cn(buttonVariants({ variant, size }), className)} {...props} />;
}
