import { createContext, useContext } from 'react';

export const PositionSharingContext = createContext({
  sharing: false,
  setSharing: () => {},
});

export function usePositionSharing() {
  return useContext(PositionSharingContext);
}
