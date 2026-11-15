// The people in the space, shared by every page: names and the colour each
// person keeps everywhere (the first to join is red, the second orange).
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, type Space } from './api';

interface SpaceState {
  space: Space | null;
  setSpace: (s: Space) => void;
}

const SpaceContext = createContext<SpaceState>({ space: null, setSpace: () => {} });

export function SpaceProvider({ children }: { children: ReactNode }) {
  const [space, setSpace] = useState<Space | null>(null);
  useEffect(() => {
    api.space().then(setSpace, () => {}); // pages still work without names
  }, []);
  return <SpaceContext.Provider value={{ space, setSpace }}>{children}</SpaceContext.Provider>;
}

export function useSpace() {
  return useContext(SpaceContext);
}

/** "a" for the first member, "b" for the second, by name. */
export function personTone(space: Space | null, name: string): 'a' | 'b' {
  const i = space?.members.findIndex((m) => m.name === name) ?? -1;
  return i === 1 ? 'b' : 'a';
}

/** A round badge with the person's first letter. */
export function Avatar({ name, tone, size }: { name: string; tone: 'a' | 'b'; size?: 'lg' }) {
  return (
    <span className={`avatar ${tone}${size ? ` ${size}` : ''}`} aria-hidden="true">
      {(name.trim()[0] ?? '?').toUpperCase()}
    </span>
  );
}
