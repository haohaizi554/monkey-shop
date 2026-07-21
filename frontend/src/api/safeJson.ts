import { isInteger, isSafeNumber, parse } from 'lossless-json'

function parseApiNumber(value: string): number | string {
  if (isInteger(value) && !isSafeNumber(value)) {
    return value
  }
  return Number(value)
}

export function parseApiJson(text: string): unknown {
  return parse(text, undefined, { parseNumber: parseApiNumber })
}
