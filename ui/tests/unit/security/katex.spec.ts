import {createRequire} from "node:module"

import {describe, expect, it} from "vitest"

const require = createRequire(import.meta.url)
const katex = createRequire(require.resolve("mermaid"))("katex")

describe("Mermaid's KaTeX dependency", () => {
    it("keeps inherited trust disabled for CVE-2026-103923", () => {
        const options = Object.create({trust: true})

        const html = katex.renderToString(String.raw`\href{javascript:alert(1)}{click}`, options)

        expect(html).not.toContain("href=\"javascript:")
    })

    it("renders normal mathematical expressions through the existing API", () => {
        expect(katex.renderToString("x^2")).toContain("class=\"katex\"")
    })
})
