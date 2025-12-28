package com.llmagent.common

import zio.*
import zio.test.*
import zio.test.Assertion.*

object CodeCleanerSpec extends ZIOSpecDefault:

  def spec = suite("CodeCleaner")(
    test("removes python code blocks") {
      val input = "```python\nprint('hello')\n```"
      assertTrue(CodeCleaner.cleanMarkdownCode(input) == "print('hello')")
    },
    test("removes py code blocks") {
      val input = "```py\nprint('hello')\n```"
      assertTrue(CodeCleaner.cleanMarkdownCode(input) == "print('hello')")
    },
    test("removes generic code blocks") {
      val input = "```\nprint('hello')\n```"
      assertTrue(CodeCleaner.cleanMarkdownCode(input) == "print('hello')")
    },
    test("handles code without blocks") {
      val input = "print('hello')"
      assertTrue(CodeCleaner.cleanMarkdownCode(input) == "print('hello')")
    },
    test("trims whitespace") {
      val input = "  \n```python\ncode\n```\n  "
      assertTrue(CodeCleaner.cleanMarkdownCode(input) == "code")
    },
    test("handles empty input") {
      assertTrue(CodeCleaner.cleanMarkdownCode("") == "")
    },
    test("handles whitespace-only input") {
      assertTrue(CodeCleaner.cleanMarkdownCode("   \n\t  ") == "")
    },
    test("handles multiline code") {
      val input = """```python
def hello():
    print('hello')
    return 42
```"""
      val expected = """def hello():
    print('hello')
    return 42"""
      assertTrue(CodeCleaner.cleanMarkdownCode(input) == expected)
    },
    test("handles code with backticks inside") {
      val input = "```python\nprint('`hello`')\n```"
      assertTrue(CodeCleaner.cleanMarkdownCode(input) == "print('`hello`')")
    }
  )
