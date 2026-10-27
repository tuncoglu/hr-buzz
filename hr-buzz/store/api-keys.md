# Getting an API key for the HR Buzz coach

_Prices checked 27 October 2026 on each service's own pricing page. They change, so check the page for your service before you add credit._

The HR Buzz coach is optional. It needs an **API key** from one AI service. Paste the key into HR Buzz under Coach settings: the app recognises which service it belongs to, checks it, and picks a small, inexpensive model by itself. Coach settings then shows the service and model it chose.

**Why a key and not a sign-in?** A ChatGPT, Claude or Gemini subscription covers that company's own app. The API is billed separately, by use, and Anthropic does not allow other apps to sign in with a Claude account. For a coach that makes a handful of requests a day, an API key usually costs far less than a subscription.

## What it costs

A typical day uses a morning plan, a debrief after your session and a couple of questions. Measured from the size of the app's own requests (about 60 sessions over 12 weeks, 9,000 to 13,000 input tokens per request), that is about **42,000 input and 11,500 output tokens a day**: roughly **1.3 million input and 0.35 million output tokens a month**. The model's reasoning counts as output.

| Service | Model HR Buzz picks | Price per 1M tokens (in / out) | About, per month |
|---|---|---|---|
| OpenAI (ChatGPT) | newest "luna" or "mini", e.g. `gpt-6-luna` | $0.10 / $0.50 | **$0.30** |
| Claude (Anthropic) | newest Haiku, e.g. `claude-haiku-5-5` | $0.10 / $0.50 | **$0.30** |
| Gemini (Google) | newest Flash, e.g. `gemini-3.8-flash` | $0.75 / $3.75 until 31 Dec 2026, then $1.50 / $7.50 | **$2.20**, then **$4.50** |
| DeepSeek | `deepseek-flash` | $0.15 / $0.60 off-peak, double at peak hours | **$0.40** to **$0.80** |

- DeepSeek's peak hours are 01:00 to 04:00 and 06:00 to 10:00 UTC, Monday to Friday; all other times are off-peak.
- Older small models cost more per token: `gpt-5-mini` ($0.25 / $2.00) is about $1.00 a month, `gpt-5.4-mini` ($0.75 / $4.50) about $2.50.
- The larger models, such as Claude Sonnet or OpenAI's "sol" tier, would cost about $6 a month at the same use. HR Buzz does not choose them.
- These are estimates. Chatting with the coach a lot raises them; a quiet week lowers them. Most services let you set a monthly spending limit: do that.

Official pricing pages:
[OpenAI](https://developers.openai.com/api/docs/pricing) ·
[Claude](https://platform.claude.com/docs/en/about-claude/pricing) ·
[Gemini](https://ai.google.dev/gemini-api/docs/pricing) ·
[DeepSeek](https://api-docs.deepseek.com/quick_start/pricing)

## How to get a key

Each service shows a key **once**, when you create it: copy it straight away and paste it into HR Buzz. Treat it like a password.

### OpenAI (ChatGPT)
1. Open [platform.openai.com/api-keys](https://platform.openai.com/api-keys) and sign in.
2. Add a few dollars of credit under Billing.
3. Choose **Create new secret key**, copy it.

### Claude (Anthropic)
1. Open [platform.claude.com/settings/keys](https://platform.claude.com/settings/keys) and sign in.
2. Add a few dollars of credit under Billing.
3. Choose **Create key**, copy it.

### Gemini (Google)
1. Open [aistudio.google.com/apikey](https://aistudio.google.com/apikey) and sign in with a Google account.
2. Choose **Create API key**, copy it.
3. **Turn on billing for the key's project before you use it for coaching.** Google's pricing page says content sent on the free tier may be used to improve Google's products and that paid-tier content is not. The coach sends your training and health summary, so use the paid tier.

### DeepSeek
1. Open [platform.deepseek.com/api_keys](https://platform.deepseek.com/api_keys) and sign in.
2. Top up your balance.
3. Choose **Create new API key**, copy it.

## Then

1. In HR Buzz open **Coach**, paste the key and leave the screen. HR Buzz checks it and shows "Using ..." with the service and model.
2. If a key is wrong, HR Buzz says so and does not keep it. If you paste a key by mistake somewhere else, delete it on the service's page and create a new one.
3. To switch service, paste a key from another one.

## What is sent

Only when the coach is on, and only to the service your key belongs to: session totals, zones, splits, notes, resting-test results, check-ins, and your age, sex, weight and max heart rate. Never GPS, second-by-second heart rate, or your name. Chest-pain and similar symptoms are answered on the phone and never sent. The service's own terms and privacy policy apply to what it receives; read them before you turn the coach on. Full details are in the [privacy policy](/privacy/).
