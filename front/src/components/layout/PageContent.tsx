import type { HTMLAttributes, ReactNode } from 'react';

type Props = HTMLAttributes<HTMLDivElement> & { children: ReactNode };

/** 列表与概览页面统一内容边界，编辑器页面不使用该容器。 */
export default function PageContent({ children, className = '', ...rest }: Props) {
  return <div className={`page-content ${className}`.trim()} {...rest}>{children}</div>;
}
