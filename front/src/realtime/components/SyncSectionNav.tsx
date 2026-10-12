import { useEffect, useState } from 'react';

export interface SyncSectionNavItem {
  key: string;
  label: string;
  children?: SyncSectionNavItem[];
}

interface Props {
  items: SyncSectionNavItem[];
  prefix: string;
}

/** 实时同步长表单的轻量锚点导航，避免依赖页面级滚动容器。 */
export default function SyncSectionNav({ items, prefix }: Props) {
  const leafItems = items.flatMap((item) => item.children ?? [item]);
  const [active, setActive] = useState(leafItems[0]?.key ?? '');

  useEffect(() => {
    if (typeof window === 'undefined' || !('IntersectionObserver' in window)) return undefined;
    const sections = leafItems
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

  const navButton = (item: SyncSectionNavItem) => <button key={item.key} type="button" className={active === item.key ? 'active' : ''}
    onClick={() => {
      setActive(item.key);
      document.getElementById(`${prefix}-${item.key}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }}>
    {item.label}
  </button>;

  return <nav className="sync-section-nav" aria-label="配置分组导航">
    {items.map((item) => item.children
      ? <div className="sync-section-nav-group" key={item.key}>
          <span className="sync-section-nav-group-label">{item.label}</span>
          {item.children.map((child) => navButton(child))}
        </div>
      : navButton(item))}
  </nav>;
}
