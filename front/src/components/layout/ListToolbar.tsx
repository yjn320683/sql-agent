import type { HTMLAttributes, ReactNode } from 'react';

type Props = HTMLAttributes<HTMLDivElement> & { children: ReactNode };

/** 列表筛选、搜索与主要操作的统一工具栏。 */
export default function ListToolbar({ children, className = '', ...rest }: Props) {
  return <div className={`list-toolbar ${className}`.trim()} {...rest}>{children}</div>;
}
