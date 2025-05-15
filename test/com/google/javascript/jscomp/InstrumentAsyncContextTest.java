/*
 * Copyright 2024 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the InstrumentAsyncContext compiler pass. */
@RunWith(JUnit4.class)
public final class InstrumentAsyncContextTest extends CompilerTestCase {

  private boolean instrumentAwait = true;

  private static final boolean SUPPORT_TOP_LEVEL_AWAIT = false;

  private static final String EXTERNS =
      """
      /** @const */
      var AsyncContext = {};

      AsyncContext.wrap = function(f) {};
      /** @constructor */
      AsyncContext.Snapshot = function() {};
      /**
       * @param {!Function} f
       * @return {!Function}
       */
      AsyncContext.Snapshot.prototype.run = function(f) {};
      /**
       * @constructor
       * @param {string=} name
       * @param {*=} defaultValue
       */

      AsyncContext.Variable = function(name, defaultValue) {};
      /** @return {*} */
      AsyncContext.Variable.prototype.get = function() {};
      /**
       * @param {*} value
       * @param {!Function} f
       * @return {*}
       */
      AsyncContext.Variable.prototype.run = function(value, f) {};

      /** @const */
      var console = {};
      console.log = function(arg) {};
      """;

  public InstrumentAsyncContextTest() {
    super(EXTERNS);
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    instrumentAwait = true;
    // setLanguageOut(LanguageMode.ECMASCRIPT5);
  }

  @Override
  protected CompilerPass getProcessor(Compiler compiler) {
    return new InstrumentAsyncContext(compiler, instrumentAwait);
  }

  @Override
  protected CompilerOptions getOptions() {
    CompilerOptions options = super.getOptions();
    options.setWarningLevel(DiagnosticGroups.MISSING_POLYFILL, CheckLevel.WARNING);
    return options;
  }

  // BEFORE: async function f() { BODY }
  //  AFTER: async function f() {
  //           var factory = start(); var suspend = factory(); var resume = factory(1);
  //           try { BODY } finally { suspend() }
  //         }
  //
  // BEFORE: await EXPR
  //  AFTER: resume(await suspend(EXPR))
  //
  // Explanation:
  //   * We compute the expression under the function context, then suspend only for the await, and
  //     resume again immediately after the await.
  //   * We also wrap the entire async body in a try-finally with an suspend() so that the function
  //     context is purged at the end.
  //   * Note that suspend() is only required after a resume(), but it's not harmful to do it if the
  //     try block were to throw before any awaits, since the initial "restored outer context" is
  //     the same as the function context - i.e. suspend before resume is a no-op.
  @Test
  public void testAwait() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          await 1;
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            ᵃᶜresume(await ᵃᶜsuspend(1));
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // BEFORE: for await (const x of EXPR) { BODY }
  //  AFTER: for await (const x of suspend(EXPR)) {
  //           resume(); try { BODY } finally { suspend() }
  //         }
  //         resume()
  //
  // Explanation:
  //   * each iteration of the for-await loop is effectively an await; the top of the loop body is
  //     always the entrypoint, but there is an suspend immediately after evaluating the async
  //     iterator subject, as well as at the end of the loop body.
  //   * after the loop, we resume because it requires an await to determine the iterator is "done"
  @Test
  public void testForAwait() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          for await (const x of gen()) {
            use(x);
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            for await (const x of ᵃᶜsuspend(gen())) {
              ᵃᶜresume();
              try {
                use(x);
              } finally {
                ᵃᶜsuspend();
              }
            }
            ᵃᶜresume();
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // TODO: b/371578480 - test break and continue inside for-await

  // A block is added if necessary so that the post-loop resume call is appropriately sequenced.
  @Test
  public void testForAwaitNotInBlock() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          if (1)
            for await (const x of gen()) {
              use(x);
            }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            if (1) {
              for await (const x of ᵃᶜsuspend(gen())) {
                ᵃᶜresume();
                try {
                  use(x);
                } finally {
                  ᵃᶜsuspend();
                }
              }
              ᵃᶜresume();
            }
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // Await nodes within the for-await should also be instrumented appropriately.
  @Test
  public void testForAwaitMixedWithAwait() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          for await (const x of await gen()) {
            await use(x);
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            for await (const x of ᵃᶜsuspend(ᵃᶜresume(await ᵃᶜsuspend(gen())))) {
              ᵃᶜresume();
              try {
                ᵃᶜresume(await ᵃᶜsuspend(use(x)));
              } finally {
                ᵃᶜsuspend();
              }
            }
            ᵃᶜresume();
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // NOTE: This test is disabled because we do not yet support top-level await.  But if it becomes
  // supported, this is how we should handle it.
  @Test
  public void testTopLevelAwait() {
    if (!SUPPORT_TOP_LEVEL_AWAIT) {
      return;
    }
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        await 1;
        await 2;
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        var ᵃᶜfactory = $jscomp.asyncContextStart();
        var ᵃᶜsuspend = ᵃᶜfactory();
        var ᵃᶜresume = ᵃᶜfactory(1);
        ᵃᶜresume(await ᵃᶜsuspend(1));
        ᵃᶜresume(await ᵃᶜsuspend(2));
        """);
  }

  // Multiple awaits should each get instrumented separately, but the top-level function
  // instrumentation should only happen once.
  @Test
  public void testMultipleAwaits() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          await 1;
          await 2;
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            ᵃᶜresume(await ᵃᶜsuspend(1));
            ᵃᶜresume(await ᵃᶜsuspend(2));
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // BEFORE: try { ... } catch { BODY }
  //  AFTER: try { ... } catch { resume(); BODY }
  //
  // Explanation:
  //   * If the immediate body of a "try" node has an await in it, and the await throws, then the
  //     resume call around the await will not run.  Instead, control flow returns to the function
  //     body via the "catch" or "finally" block, so we need to additionally insert the resume call
  //     at the top of either catch or finally, whichever comes first.
  @Test
  public void testAwaitInsideTryCatch() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          try {
            await 1;
          } catch (e) {
            console.log(e);
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            try {
              ᵃᶜresume(await ᵃᶜsuspend(1));
            } catch (e) {
              ᵃᶜresume();
              console.log(e);
            }
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // BEFORE: try { ... } catch { BODY1 } finally { BODY2 }
  //  AFTER: try { ... } catch { resume(); BODY1 } finally { BODY2 }
  //
  // Explanation:
  //   * If there is both a "catch" and a "finally", then only the former needs a resume call.
  //   * The "finally" block will _only_ run if the "catch" block terminates normally, so we can
  //     guarantee that the context will have resumeed before we reach it.
  @Test
  public void testAwaitInsideTryCatchFinally() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          try {
            await 1;
          } catch (e) {
            console.log(e);
          } finally {
            console.log('finally');
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            try {
              ᵃᶜresume(await ᵃᶜsuspend(1));
            } catch (e) {
              ᵃᶜresume();
              console.log(e);
            } finally {
              console.log('finally');
            }
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // BEFORE: try { ... } finally { BODY }
  //  AFTER: try { ... } finally { resume(); BODY }
  @Test
  public void testAwaitInsideTryFinally() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          try {
            await 1;
          } finally {
            console.log('finally');
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            try {
              ᵃᶜresume(await ᵃᶜsuspend(1));
            } finally {
              ᵃᶜresume();
              console.log('finally');
            }
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // `return await` does not require any instrumentation if it's not inside a try block.
  @Test
  public void testReturnAwait() {
    testSame(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          return await 1;
        }
        """);
  }

  // `return await` does not require any instrumentation if it's not inside a try block.
  // But if there's a catch block, then the function context may still get resumeed after the
  // await, if the awaited promise is rejected.  So this should be instrumented normally.
  @Test
  public void testReturnAwaitInsideTryCatch() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          try {
            return await 1;
          } catch (e) {
            console.log(e);
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            try {
              return ᵃᶜresume(await ᵃᶜsuspend(1));
            } catch (e) {
              ᵃᶜresume();
              console.log(e);
            }
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // `return await` does not require any instrumentation if it's not inside a try block.
  // But if there's a finally, then the function context will still get resumeed after the await.
  @Test
  public void testReturnAwaitInsideTryFinally() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          try {
            return await 1;
          } finally {
            console.log('finally');
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            try {
              return ᵃᶜresume(await ᵃᶜsuspend(1));
            } finally {
              ᵃᶜresume();
              console.log('finally');
            }
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }

  // `return await` does not require any instrumentation if it's the entire arrow function
  // expression.  There is no "start" call, either, since we will never need to restore the
  // context after the await (there's no code there that would need it).
  @Test
  public void testAwaitImmediatelyReturnedFromBodylessArrow() {
    testSame(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        (async () => await 1)();
        """);
  }

  // A shorthand arrow with a nested await needs instrumentation, since there's ECMAScript code that
  // executes after the await.  Promote the shorthand arrow into a full block, with a proper return
  // statement, etc.
  @Test
  public void testAwaitInsideBodylessArrow() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        (async () => use(await 1))();
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        (async () => {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            return use(ᵃᶜresume(await ᵃᶜsuspend(1)));
          } finally {
            ᵃᶜsuspend();
          }
        })();
        """);
  }

  // Instrumentation is tied to `await`, not `async`.  If there is no await, then there's no reentry
  // and we don't need to save the entry context to restore later.  No instrumentation is required.
  @Test
  public void testAsyncFunctionWithNoAwait() {
    testSame(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          return 1;
        }
        """);
  }

  // BEFORE: function* f() { BODY }
  //  AFTER: function f() {
  //           const factory = start(1);
  //           const suspend = factory();
  //           const resume = factory(1);
  //           return function*() { resume(); try { BODY } finally { suspend() } }()
  //         }
  //
  // BEFORE: yield EXPR
  //  AFTER: resume(yield suspend(EXPR), 1)
  //
  // Explanation:
  //   * in order to snapshot the context from the initial call, we need to wrap the generator into
  //     an ordinary function to call start() outside the generator, and then we use an immediately
  //     invoked generator to run the body, which now needs an explit resume() at the front, since
  //     it may happen at some later time in a different context.
  //   * a generator's "function context" is snapshotted when the generator is first called, but no
  //     ECMAScript code runs at that time - the generator body doesn't begin until the first call
  //     to iter.next(): start(1) indicates that the context should start as "suspended".
  @Test
  public void testGenerator() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function* f() {
          yield 1;
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return function*() {
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(1));
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // yield has the same behavior as await w.r.t. try blocks
  @Test
  public void testGeneratorWithTryCatchFinally() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function* f() {
          try {
            yield 1;
          } catch (e) {
            yield 2;
          } finally {
            yield 3;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return function*() {
            ᵃᶜresume();
            try {
              try {
                ᵃᶜresume(yield ᵃᶜsuspend(1));
              } catch (e) {
                ᵃᶜresume();
                ᵃᶜresume(yield ᵃᶜsuspend(2));
              } finally {
                ᵃᶜresume(yield ᵃᶜsuspend(3));
              }
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // yield has the same behavior as await w.r.t. try blocks
  @Test
  public void testGeneratorWithTryFinally() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function* f() {
          try {
            yield 1;
          } finally {
            yield 3;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return function*() {
            ᵃᶜresume();
            try {
              try {
                ᵃᶜresume(yield ᵃᶜsuspend(1));
              } finally {
                ᵃᶜresume();
                ᵃᶜresume(yield ᵃᶜsuspend(3));
              }
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // yield has the same behavior as await w.r.t. try blocks: only the inner try-catch gets
  // instrumented with a re-start.
  @Test
  public void testGeneratorWithNestedTry() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function* f() {
          try {
            try {
              yield 1;
            } catch (e) {
              console.log(e);
            }
          } catch (e) {
            console.log(e);
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return function*() {
            ᵃᶜresume();
            try {
              try {
                try {
                  ᵃᶜresume(yield ᵃᶜsuspend(1));
                } catch (e) {
                  ᵃᶜresume();
                  console.log(e);
                }
              } catch (e) {
                console.log(e);
              }
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // Empty yield is a special case that wasn't present with await (you can't have an empty await).
  // In this case, the suspend() call has an implicit "undefined" for the first argument, which
  // evaluates to `yield undefined`, which is equivalent to just `yield`.
  @Test
  public void testGeneratorWithEmptyYield() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function* f() {
          yield;
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return function*() {
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend());
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // Yield-less generators still need instrumentation, unlike await-less async functions, since the
  // generator body may run at a later time and needs to have the initial context restored.
  @Test
  public void testGeneratorWithNoYield() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function* f() {
          console.log(1);
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return function*() {
            ᵃᶜresume();
            try {
              console.log(1);
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // No instrumentation is needed because there's no code inside to observe any variables.
  @Test
  public void testGeneratorWithEmptyBody() {
    testSame(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function* f() {}
        """);
  }

  // Because of the nested generator (which cannot be an arrow because generator arrow don't exist),
  // we need to indirect access to "arguments" and/or "this".
  @Test
  public void testGeneratorWithArguments() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function* f(a) {
          yield a + arguments[1];
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f(a) {
          const $jscomp$arguments$m1146332801$0 = arguments;
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return function*() {
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(a + $jscomp$arguments$m1146332801$0[1]));
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // Generator methods are transpiled the same as generator functions, as long as there is no
  // reference to "super".
  @Test
  public void testGeneratorMethod() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f() {
            yield 1;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f() {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            return (function*() {
              ᵃᶜresume();
              try {
                ᵃᶜresume(yield ᵃᶜsuspend(1));
              } finally {
                ᵃᶜsuspend();
              }
            })();
          }
        }
        """);
  }

  // Generator methods are transpiled the same as generator functions, as long as there is no
  // reference to "super".
  @Test
  public void testGeneratorMethodWithNoYield() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f() {
            console.log(42);
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f() {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            return (function*() {
              ᵃᶜresume();
              try {
                console.log(42);
              } finally {
                ᵃᶜsuspend();
              }
            })();
          }
        }
        """);
  }

  // Generator methods are transpiled the same as generator functions, as long as there is no
  // reference to "super".  In this case, the "this" needs to be stored, since the "function*"
  // would clobber it.
  //
  // TODO(sdh): we may be able to use .apply(this, arguments) instead, though this could possibly
  // impact performance negatively.
  @Test
  public void testGeneratorMethodWithThis() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *g() {
            yield this;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          g() {
            const $jscomp$this$m1146332801$0 = this;
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            return (function*() {
              ᵃᶜresume();
              try {
                ᵃᶜresume(yield ᵃᶜsuspend($jscomp$this$m1146332801$0));
              } finally {
                ᵃᶜsuspend();
              }
            })();
          }
        }
        """);
  }

  // Generator methods are transpiled the same as generator functions, as long as there is no
  // reference to "super".
  @Test
  public void testGeneratorMethodWithArguments() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *g() {
            yield arguments.length;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          g() {
            const $jscomp$arguments$m1146332801$0 = arguments;
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            return (function*() {
              ᵃᶜresume();
              try {
                ᵃᶜresume(yield ᵃᶜsuspend($jscomp$arguments$m1146332801$0.length));
              } finally {
                ᵃᶜsuspend();
              }
            })();
          }
        }
        """);
  }

  // Generator methods that reference "super" need special handling because there's no way to pass
  // the super through the immediately invoked generator wrapped inside.  Instead, we define an
  // additional separate method, to which we pass the context, as well as any arguments.  The new
  // method should match the original in terms of static-vs-instance.
  @Test
  public void testGeneratorMethodSuperStatic() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          /** @nocollapse */
          static *f() {
            yield* super.f();
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          /** @nocollapse */
          static f() {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(ᵃᶜfactory);
          }
          /** @nocollapse */
          static *f$jscomp$m1146332801$0(ᵃᶜfactory) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield* ᵃᶜsuspend(super.f()));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // Generator methods that reference "super" need special handling because there's no way to pass
  // the super through the immediately invoked generator wrapped inside.  Instead, we define an
  // additional separate method, to which we pass the context, as well as any arguments.  The new
  // method should match the original in terms of static-vs-instance.
  @Test
  public void testGeneratorMethodSuper() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f() {
            yield super.f();
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f() {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(ᵃᶜfactory);
          }
          *f$jscomp$m1146332801$0(ᵃᶜfactory) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(super.f()));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // This is the same as the case with yield.
  @Test
  public void testGeneratorMethodSuperWithNoYield() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f() {
            return super.f();
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f() {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(ᵃᶜfactory);
          }
          *f$jscomp$m1146332801$0(ᵃᶜfactory) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              return super.f();
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // Parameters are copied over and forwarded to the new method.
  @Test
  public void testGeneratorMethodSuperWithParameters() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f(a, b, c) {
            yield super.x;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f(a, b, c) {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(ᵃᶜfactory, a, b, c);
          }
          *f$jscomp$m1146332801$0(ᵃᶜfactory, a, b, c) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(super.x));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // Because of the additional context parameter, we can't just read arguments directly in the inner
  // generator.  Instead, we pass the arguments array explicitly as its own parameter, if it's
  // accessed from within the generator body.
  @Test
  public void testGeneratorMethodSuperReadsArgumentsArray() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f(a) {
            yield super.x + arguments.length;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f(a) {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(ᵃᶜfactory, arguments, a);
          }
          *f$jscomp$m1146332801$0(ᵃᶜfactory, $jscomp$arguments$m1146332801$1, a) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(super.x + $jscomp$arguments$m1146332801$1.length));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // Default parameter initializers are retained on the inner method, rather than the outer method.
  // This is required so that they will be correctly excluded from arguments arrays (i.e. if you
  // write `function f(x = 1) { return arguments[0]; }` then you can distinguish a call of `f()`
  // from a call of `f(1)` since the former returns `undefined` (and the array has length 0).
  @Test
  public void testGeneratorMethodSuperWithDefaultParameter() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f(a = 1) {
            yield super.y;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f(a) {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(ᵃᶜfactory, a);
          }
          *f$jscomp$m1146332801$0(ᵃᶜfactory, a = 1) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(super.y));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // Destructurd parameters are also retained on the inner method, for similar reasons to defaults.
  @Test
  public void testGeneratorMethodSuperWithDestructuredParameters() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f({a}, [b = 1]) {
            yield super.x;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f($jscomp$m1146332801$1, $jscomp$m1146332801$2) {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(
                ᵃᶜfactory,
                $jscomp$m1146332801$1,
                $jscomp$m1146332801$2);
          }
          *f$jscomp$m1146332801$0(ᵃᶜfactory, {a}, [b = 1]) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(super.x));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // Rest parameters are copied on both the original generator-turned-ordinary-method as well as on
  // the new inner generator, since they're important for passing along all the arguments.
  @Test
  public void testGeneratorMethodSuperWithRestParameter() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f(...a) {
            yield super.foo;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f(...a) {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(ᵃᶜfactory, ...a);
          }
          *f$jscomp$m1146332801$0(ᵃᶜfactory, ...a) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(super.foo));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // This case just combines all the earlier cases into one.
  @Test
  public void testGeneratorMethodSuperWithComplicatedParameters() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *f(a, {b}, c = 1, ...[d]) {
            yield super.foo;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f(a, $jscomp$m1146332801$1, c, ...$jscomp$m1146332801$2) {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(
                ᵃᶜfactory, a, $jscomp$m1146332801$1, c, ...$jscomp$m1146332801$2);
          }
          *f$jscomp$m1146332801$0(ᵃᶜfactory, a, {b}, c = 1, ...[d]) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(super.foo));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // When the generator method has a computed key, we can't include the name in the new inner
  // generator.
  @Test
  public void testGeneratorMethodSuperWithComputedKey() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          *[Symbol.iterator]() {
            yield super.foo();
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          [Symbol.iterator]() {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.$jscomp$m1146332801$0(ᵃᶜfactory);
          }
          *$jscomp$m1146332801$0(ᵃᶜfactory) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(super.foo()));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // Async generators combine both async and generator transpilations into one.
  @Test
  public void testAsyncGenerator() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function* f() {
          yield await 1;
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return async function*() {
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(ᵃᶜresume(await ᵃᶜsuspend(1))));
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // The generator transpilation is still required, even when there's no yields or awaits.
  @Test
  public void testAsyncGeneratorWithNoYieldNorAwait() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function* f() {
          return 1;
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return async function*() {
            ᵃᶜresume();
            try {
              return 1;
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // Async generator methods are transpiled the same as functions as long as there is no super.
  @Test
  public void testAsyncGeneratorMethod() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          async *f() {
            yield await 1;
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f() {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            return (async function*() {
              ᵃᶜresume();
              try {
                ᵃᶜresume(yield ᵃᶜsuspend(ᵃᶜresume(await ᵃᶜsuspend(1))));
              } finally {
                ᵃᶜsuspend();
              }
            })();
          }
        }
        """);
  }

  // Async generator methods get the same treatment as generator methods if they access super.
  @Test
  public void testAsyncGeneratorMethodSuper() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          async *f() {
            yield await super.f();
          }
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        class Foo {
          f() {
            var ᵃᶜfactory = $jscomp.asyncContextStart(1);
            return this.f$jscomp$m1146332801$0(ᵃᶜfactory);
          }
          async *f$jscomp$m1146332801$0(ᵃᶜfactory) {
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(ᵃᶜresume(await ᵃᶜsuspend(super.f()))));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        """);
  }

  // Test that we don't do anything with async functions when instrumentAwait is false.
  // This option is used when the output level is less than ES2017.  In that case, async functions
  // transpile down to generators with ordinary Promise objects, and the AsyncContext.Variable
  // runtime polyfill will already make the necessary monkey-patches to Promise so that these get
  // instrumented for free.
  @Test
  public void testNoInstrumentAwait_await() {
    instrumentAwait = false;
    testSame(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          await 1;
        }
        """);
  }

  // No "await" instrumentation.
  @Test
  public void testNoInstrumentAwait_forAwait() {
    instrumentAwait = false;
    testSame(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          for await (const x of gen()) {
            use(x);
          }
        }
        """);
  }

  // This test is unaffected by instrumentAwait=false because generators still need to be
  // instrumented even when async functions are rewritten.  Generators are rewritten for all
  // language-out levels, including ES5 which transpiles the generator away.
  @Test
  public void testNoInstrumentAwait_generator() {
    instrumentAwait = false;
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function* f() {
          yield 1;
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return function*() {
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(1));
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // Test that only the yield is instrumented, but that awaits are left alone.
  @Test
  public void testNoInstrumentAwait_asyncGenerator() {
    instrumentAwait = false;
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function* f() {
          yield await 1;
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart(1);
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          return async function*() {
            ᵃᶜresume();
            try {
              ᵃᶜresume(yield ᵃᶜsuspend(await 1));
            } finally {
              ᵃᶜsuspend();
            }
          }();
        }
        """);
  }

  // Test that if the input is already instrumented, we don't double-instrument.
  @Test
  public void testAlreadyInstrumented() {
    testSame(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          ᵃᶜresume(await ᵃᶜsuspend(42));
        }
        """);
  }

  // Already-instrumented check applies on the function level: `f` is skipped, but
  // `g` and `h` get instrumentation.
  @Test
  public void testAlreadyInstrumented_partial() {
    test(
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          ᵃᶜresume(await ᵃᶜsuspend(2));
          async function g() {
            await 3;
          }
        }
        async function h() {
          await 4;
        }
        """,
        """
        const v = new AsyncContext.Variable();
        v.run(100, () => {});
        async function f() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          ᵃᶜresume(await ᵃᶜsuspend(2));
          async function g() {
            var ᵃᶜfactory = $jscomp.asyncContextStart();
            var ᵃᶜsuspend = ᵃᶜfactory();
            var ᵃᶜresume = ᵃᶜfactory(1);
            try {
              ᵃᶜresume(await ᵃᶜsuspend(3));
            } finally {
              ᵃᶜsuspend();
            }
          }
        }
        async function h() {
          var ᵃᶜfactory = $jscomp.asyncContextStart();
          var ᵃᶜsuspend = ᵃᶜfactory();
          var ᵃᶜresume = ᵃᶜfactory(1);
          try {
            ᵃᶜresume(await ᵃᶜsuspend(4));
          } finally {
            ᵃᶜsuspend();
          }
        }
        """);
  }
}
