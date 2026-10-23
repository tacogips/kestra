import {createRequire} from "node:module"

import {describe, expect, it} from "vitest"

const braces = createRequire(import.meta.url)("braces")
const methods = ["compile", "expand", "stringify"] as const

interface BraceNode {
    type: string;
    nodes?: BraceNode[];
    value?: string;
}

describe("CVE-2026-93687 mitigation", () => {
    it.each(methods)("rejects deeply nested patterns in %s before exhausting the stack", (method) => {
        const pattern = "{".repeat(4000) + "a,b" + "}".repeat(4000)

        expect(() => braces[method](pattern)).toThrow("maximum depth of 100")
    })

    it.each(methods)("rejects deeply nested external ASTs in %s", (method) => {
        let ast: BraceNode = {type: "text", value: "a"}
        for (let depth = 0; depth < 4000; depth++) {
            ast = {type: "root", nodes: [ast]}
        }

        expect(() => braces[method](ast)).toThrow("maximum depth of 100")
    })

    it("expands normal glob patterns", () => {
        expect(braces.expand("src/{a,b}.js")).toEqual(["src/a.js", "src/b.js"])
    })

    it("keeps escaped braces literal without counting them as nesting", () => {
        expect(braces.stringify("\\{".repeat(1000))).toBe("{".repeat(1000))
    })
})
