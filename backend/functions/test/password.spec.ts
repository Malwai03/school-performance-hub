import { readFileSync } from "node:fs";
import { join } from "node:path";
import { generateTemporaryPassword, TEMP_PASSWORD_ALPHABET } from "../src/password";

describe("temporary passwords", () => {
  test("10 characters from the alphabet plus a trailing !", () => {
    const password = generateTemporaryPassword();
    expect(password).toHaveLength(11);
    expect(password.endsWith("!")).toBe(true);
    for (const ch of password.slice(0, -1)) {
      expect(TEMP_PASSWORD_ALPHABET).toContain(ch);
    }
  });

  test("custom length and a floor of 8 random characters", () => {
    expect(generateTemporaryPassword(16)).toHaveLength(17);
    expect(() => generateTemporaryPassword(7)).toThrow(RangeError);
    expect(() => generateTemporaryPassword(9.5)).toThrow(RangeError);
  });

  test("no repeats across many draws and every character can appear", () => {
    const draws = Array.from({ length: 2000 }, () => generateTemporaryPassword());
    expect(new Set(draws).size).toBe(draws.length);
    const seen = new Set(draws.join("").replace(/!/g, ""));
    expect(seen.size).toBe(TEMP_PASSWORD_ALPHABET.length);
  });

  test("account creation and admin reset use the secure generator, never Math.random", () => {
    const source = readFileSync(join(__dirname, "..", "src", "index.ts"), "utf8");
    expect(source).not.toMatch(/Math\.random/);
    expect(source).toMatch(/import \{ generateTemporaryPassword \} from "\.\/password";/);
    expect(source.match(/generateTemporaryPassword\(\)/g)?.length).toBe(2); // create + reset
  });
});
