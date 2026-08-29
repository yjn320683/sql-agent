import { useCallback, useEffect, useRef, useState } from 'react';
import type { RefCallback } from 'react';

interface Options {
  initialWidth: number;
  minWidth?: number;
  cellPadding?: number;
}

export function useAutoTableActionWidth({
  initialWidth,
  minWidth = 72,
  cellPadding = 24,
}: Options) {
  const [width, setWidth] = useState(initialWidth);
  const nodes = useRef(new Map<string | number, HTMLDivElement>());
  const frame = useRef<number>();

  const measure = useCallback(() => {
    if (frame.current !== undefined) window.cancelAnimationFrame(frame.current);
    frame.current = window.requestAnimationFrame(() => {
      let contentWidth = 0;
      nodes.current.forEach((node) => {
        contentWidth = Math.max(contentWidth, node.scrollWidth);
      });
      if (contentWidth > 0) {
        const nextWidth = Math.max(minWidth, Math.ceil(contentWidth + cellPadding));
        setWidth((current) => current === nextWidth ? current : nextWidth);
      }
    });
  }, [cellPadding, minWidth]);

  const actionRef = useCallback((key: string | number): RefCallback<HTMLDivElement> => (node) => {
    if (node) nodes.current.set(key, node);
    else nodes.current.delete(key);
    measure();
  }, [measure]);

  useEffect(() => {
    window.addEventListener('resize', measure);
    return () => {
      window.removeEventListener('resize', measure);
      if (frame.current !== undefined) window.cancelAnimationFrame(frame.current);
    };
  }, [measure]);

  return { actionColumnWidth: width, actionRef };
}
