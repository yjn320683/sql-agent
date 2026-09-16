import { useEffect, useState } from 'react';

export interface SyncSectionNavItem {
  key: string;
  label: string;
}

interface Props {
  items: SyncSectionNavItem[];
  prefix: string;
}

/** 实时同步长表单的轻量锚点导航，避免依赖页面级滚动容器。 */
export default function SyncSectionNav({ items, prefix }: Props) {
  const [active, setActive] = useState(items[0]?.key ?? '');

  useEffect(() => {
    if (typeof window === 'undefined' || !('IntersectionObserver' in window)) return undefined;
    const sections = items
      .map((item) => document.getElementById(`${prefix}-${item.key}`))
      .filter((section): section is HTMLElement => Boolean(section));
    if (sections.length === 0) return undefined;

    const observer = new IntersectionObserver((entries) => {
      const current = entries
        .filter((entry) => entry.isIntersecting)
        .sort((left, right) => left.boundingClientRect.top - right.boundingClientRect.top)[0];
      if (current) setActive(current.target.id.slice(prefix.length + 1));
    }, { rootMargin: '-12% 0px -72% 0px', threshold: 0 });
    sections.forEach((section) => observer.observe(section));
    return () => observer.disconnect();
  }, [items, prefix]);

  return <nav className="sync-section-nav" aria-label="配置分组导航">
    {items.map((item) => <button key={item.key} type="button" className={active === item.key ? 'active' : ''}
      onClick={() => {
        setActive(item.key);
        document.getElementById(`${prefix}-${item.key}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }}>
      {item.label}
    </button>)}
  </nav>;
}
