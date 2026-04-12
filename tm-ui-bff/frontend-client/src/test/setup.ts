import '@testing-library/jest-dom';

const originalWarn = console.warn;

console.warn = (...args: unknown[]) => {
  const firstArg = typeof args[0] === 'string' ? args[0] : '';
  if (firstArg.includes('React Router Future Flag Warning')) {
	return;
  }
  originalWarn(...args);
};

