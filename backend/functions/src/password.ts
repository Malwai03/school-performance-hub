import { randomInt } from "node:crypto";

// No look-alike characters (0/O, 1/l/I), so a temporary password can be read out or copied by hand.
export const TEMP_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

/**
 * Temporary account password: `length` characters drawn with Node's CSPRNG plus a trailing "!".
 * `randomInt` is uniform over its range, so there is no modulo bias. The account is still flagged
 * mustChangePassword by the caller, so this only has to survive until first sign-in.
 */
export function generateTemporaryPassword(length = 10): string {
  if (!Number.isInteger(length) || length < 8) {
    throw new RangeError("temporary passwords need at least 8 random characters");
  }
  let output = "";
  for (let i = 0; i < length; i++) {
    output += TEMP_PASSWORD_ALPHABET[randomInt(TEMP_PASSWORD_ALPHABET.length)];
  }
  return `${output}!`;
}
