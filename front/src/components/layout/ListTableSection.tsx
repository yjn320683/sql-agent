import type { HTMLAttributes, ReactNode } from 'react';

type Props = HTMLAttributes<HTMLElement> & { children: ReactNode };

/** 无额外边框的列表承载区，横向滚动由表格自身处理。 */
export default function ListTableSection({ children, className = '', ...rest }: Props) {
  return <section className={`list-table-section ${className}`.trim()} {...rest}>{children}</section>;
}
