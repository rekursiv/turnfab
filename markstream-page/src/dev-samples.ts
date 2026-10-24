//
// Hand-test fixtures for the currency-vs-math guard. Dev only: `import.meta.env.DEV`
// folds to false in the production build, so this panel never reaches the jar.
//

export interface Sample {
  name: string
  /** What this sample is supposed to prove, shown in the panel tooltip. */
  note: string
  text: string
}

export const SAMPLES: Sample[] = [
  {
    name: 'Math only',
    note: 'the reply that started all this: equations must still render',
    text: String.raw`A frontier chatbot uses roughly **0.3-0.5 kWh per 1,000 tokens**.

Power for one rack of GPUs:

$$P = \sqrt{3}\,V_{\text{LL}} I_{\text{L}} \cos\varphi$$

Annual energy for the whole site:

$$E = P \times \text{PUE} \times 8760\,\text{h}$$

with $1.1 \lesssim \text{PUE} \lesssim 1.4$. Cooling adds $\frac{P_{\text{cool}}}{P_{\text{IT}}}$
on top, and a single $H_2O$ chiller loop is rarely that efficient.`,
  },
  {
    name: 'Prices only',
    note: 'THE BUG: today KaTeX swallows everything between $312 and $198',
    text: `Cheapest flights to San Francisco (SFO), round trip from LAX, Nov 14-21:

1. United - **$312** nonstop, arrives 14:05
2. Alaska - **$289** with one stop in Phoenix
3. A low-cost carrier - **$198**, but a checked bag is $40 each

Cheapest usable total for two people, one bag each: **$1,198** before seat selection.`,
  },
  {
    name: 'Both at once',
    note: 'the case a manual on/off checkbox could never handle',
    text: String.raw`How compound interest works: you earn interest on interest, so the balance follows

$$A = P\left(1 + \frac{r}{n}\right)^{nt}$$

A $10,000 deposit at 4% compounded monthly for 5 years becomes

$A = 10000\left(1 + \frac{0.04}{12}\right)^{60} \approx \$12{,}209$

which is roughly $759 more than plain simple interest would have paid.`,
  },
  {
    name: 'Ranges after code',
    note: 'the shape of a real saved transcript: hinted fence, then price ranges',
    text: `Here's what I found. Quick note on your instincts: **you're right that $460 is high.**
Google Flights has the typical October range for PDX–SFO at **$130–$375**, so you're
above the market for this route.

\`\`\`python
# a histogram of the last 30 days
prices = [130, 375, 460]  # $ per ticket
\`\`\`

So the Alaska round trip (depart Oct 30, return Oct 31) would be roughly
$210 + $98 = $308. That's cheaper than $460, and cheaper than $211 + $233 as well.
Fare pricing is roughly $p = 155 + 12d$, where $d$ is days until departure.

Total for two people: $422, or $1,000 if you add the refundable option.`,
  },
  {
    name: 'Prices in code',
    note: 'dollars inside a fence are text: the guard must not touch them',
    text: `\`\`\`text
# Reproduce the incident
$ grep -r 'price: \$\d\+' logs/
price: $42
\`\`\`

Locally the tool reported $42 per request, while prod reported $42.50 - so the
bug is in the config, not the parser.`,
  },
]

/**
 * The four cases in the upstream report. These are meant to be handed to the
 * parser directly, not rendered: what we want to read is whether a math_inline
 * node shows up, and with what content.
 */
export const AST_CASES: { name: string, text: string }[] = [
  {
    name: '1: two prices in one sentence',
    text: 'United - **$312** nonstop, Alaska - **$289** with a stop.',
  },
  {
    name: '2: range joined by a dash',
    text: 'The October range is **$130\u2013$375**, so you are above the market.',
  },
  {
    name: '3: sum of prices',
    text: 'The Alaska round trip is roughly $210 + $98 = $308. Cheaper than $460.',
  },
  {
    name: '4: amounts and a formula together',
    text: 'A $10,000 deposit at 4%: $A = P(1 + r)^t$ grows to about $12,209.',
  },
]
