//
// Currency-vs-math guard.
//
// markstream-vue's math rule treats any unescaped `$…$` pair as inline TeX. That
// is right for `$E = mc^2$` and wrong for `fares: $312 and $289 each`, where two
// *prices* happen to form two delimiters and KaTeX eats everything between them.
//
// The library's own escape for a literal dollar is `\$`: its scanner skips
// backslash-escaped dollars, and CommonMark renders `\$` back to `$`. So this
// module rewrites only the dollars that are almost certainly money, and leaves
// real delimiters alone (`$$…$$`, `\(…\)`, `\[…\]`, `$…$` around TeX).
//
// The rule in one line: a `$` followed by digits is money, unless what separates
// it from the next `$` in the same paragraph looks like TeX.
//
// Why look ahead instead of reading the characters after the amount: a price
// chain (`$210 + $98 = $308`) and a formula (`$1.1 \lesssim \text{PUE}$`) both
// begin the same way, and no character class can tell them apart - only the
// presence or absence of TeX between the delimiters can. Everything else here is
// bookkeeping: fenced code, emphasis markers, ranges.
//
// Deliberate trade: `$2 + 3 = 5$` (an identity typed as prose-arithmetic) comes
// out literal, because the cheaper failure mode is a mangled flight price. So do
// `$\frac…$`, `$$…$$` or `\( \)` when you want math that starts with a digit.
//

/** Money-shaped amount right after a `$`: `312`, `1,198`, `42.50`. */
const AMOUNT_RE = /^\d{1,3}(?:,\d{3})*(?:\.\d+)?/

/**
 * What may follow a bare amount when the paragraph holds no second `$` at all.
 * Dashes matter for a lone price written next to a clause break (`$42.50 - so`)
 * and slashes for rates (`$20/mo`).
 */
const MONEY_TAIL_RE = /^[\s),.;:!?/–—-]/

/** An emphasis run right after the amount (`$312** nonstop`): not a multiply sign. */
const EMPHASIS_RE = /^\*{1,2}/

/** Characters that mean "this is a formula": `\pi`, `x^2`, `\text{}`, `H_2O`. */
const FORMULA_MARKER_RE = /[\\^{}_]/

/** Fence opener (may carry a language hint) or closer (may not). */
const FENCE_RE = /^ {0,3}(`{3,}|~{3,})(.*)$/

interface Fence {
  char: string
  width: number
}

function fenceOf(line: string, closing: boolean): Fence | null {
  const m = line.match(FENCE_RE)
  if (!m)
    return null
  if (closing && m[2].trim() !== '')
    return null // a closer carries no language hint
  return { char: m[1].charAt(0), width: m[1].length }
}

type Ahead = 'formula' | 'money' | 'none'

/**
 * Walks forward from just after a `$` until it finds either a formula marker or
 * the next plain `$`. Stops at a blank line: a formula that spans paragraphs is
 * not a real reply's shape, and wandering off would misread prices.
 *
 * `none` means "no partner in this paragraph", so the caller falls back to
 * reading what follows the amount.
 */
function lookAhead(lines: readonly string[], li: number, ci: number): Ahead {
  for (let l = li, pos = ci + 1; l < lines.length; l++, pos = 0) {
    const line = lines[l]
    for (let i = l === li ? pos : 0; i < line.length; i++) {
      const ch = line[i]
      if (ch === '\\') {
        if (line[i + 1] === '$')
          i++ // an escaped dollar: literal text, not a marker
        else
          return 'formula'
        continue
      }
      if (FORMULA_MARKER_RE.test(ch))
        return 'formula'
      if (ch === '$') {
        if (line[i + 1] === '$') {
          i++ // `$$` is display math, not this span's closing delimiter
          continue
        }
        return 'money'
      }
    }
    if (line.trim() === '')
      return 'none' // paragraph over
  }
  return 'none'
}

function isCurrencyDollar(lines: readonly string[], li: number, ci: number): boolean {
  const after = lines[li].slice(ci + 1)
  if (after.startsWith('$'))
    return false // `$$` is a display-math delimiter
  if (!AMOUNT_RE.test(after))
    return false // `$E`, `$x$`, `$H_2O$`: no digits, no claim of being money

  const seen = lookAhead(lines, li, ci)
  if (seen === 'formula')
    return false
  if (seen === 'money')
    return true

  // No partner in this paragraph: the amount's own surroundings decide.
  let rest = after.slice(after.match(AMOUNT_RE)![0].length)
  if (rest.startsWith('$')) {
    const afterClose = rest.slice(1)
    return afterClose === '' || MONEY_TAIL_RE.test(afterClose)
  }
  rest = rest.replace(EMPHASIS_RE, '')
  return rest === '' || MONEY_TAIL_RE.test(rest)
}

/** One line's text: escape money dollars, copy code spans through untouched. */
function escapeDollarsInText(line: string, lines: readonly string[], li: number): string {
  let out = ''
  let i = 0
  while (i < line.length) {
    const ch = line[i]
    if (ch === '\\') {
      // Already an escape (including one added on an earlier render pass).
      out += ch + (line[i + 1] ?? '')
      i += 2
      continue
    }
    if (ch === '`') {
      const close = line.indexOf('`', i + 1)
      if (close !== -1) {
        out += line.slice(i, close + 1)
        i = close + 1
        continue
      }
    }
    if (ch === '$' && isCurrencyDollar(lines, li, i)) {
      out += '\\$'
      i++
      continue
    }
    out += ch
    i++
  }
  return out
}

/**
 * Escapes currency dollars across a whole document, skipping fenced code blocks
 * and inline code spans where a `$` is text by definition.
 */
export function escapeCurrencyDollars(doc: string): string {
  if (!doc.includes('$')) {
    return doc
  }
  const lines = doc.split('\n')
  const out: string[] = []
  let fence: Fence | null = null
  for (const [li, line] of lines.entries()) {
    if (fence) {
      const closer = fenceOf(line, true)
      if (closer && closer.char === fence.char && closer.width >= fence.width)
        fence = null
      out.push(line)
      continue
    }
    const opener = fenceOf(line, false)
    if (opener) {
      fence = opener
      out.push(line)
      continue
    }
    out.push(escapeDollarsInText(line, lines, li))
  }
  return out.join('\n')
}
