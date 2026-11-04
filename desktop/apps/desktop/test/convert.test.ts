import { describe, expect, it } from "vitest";
import { getSchema } from "@tiptap/core";
import { canonical } from "@scratchy/core";
import { extensions } from "../src/lib/editor/schema";
import { docToMarkdown, markdownToDoc } from "../src/lib/editor/convert";

const schema = getSchema(extensions());
const through = (md: string) => docToMarkdown(schema.nodeFromJSON(markdownToDoc(md)));

// Opening a note in the editor and reading it back must give exactly what the phone would write,
// otherwise every note opened on the desktop would change.
describe("editor round trip", () => {
  const corpus = [
    "",
    "plain\n\nlines",
    "# Shopping\n- [ ] milk\n- [x] **eggs**\n  1. one\n  2. two\n> note this",
    "| Name | Age |\n| --- | --- |\n| Bob | 4 |\nafter",
    "```js\nlet a = 1\n\nlet b = 2\n```\ntext",
    "```\nfirst\n```\n```py\nsecond\n```",
    "mixed **bold *both* bold** ~~x~~ `y` [l](u) a_b _c_ \\* \\_",
    "**[`code link`](https://x.dev)**",
    "---\n| lone | row |",
    "اخر فترة **مهم**\n- بند\n\t- مزيد",
    "* a\n+ b\n7. c\n1) d",
    "trailing   \n   leading",
  ];
  for (const md of corpus) it(JSON.stringify(md.slice(0, 40)), () => expect(through(md)).toBe(canonical(md)));
});
