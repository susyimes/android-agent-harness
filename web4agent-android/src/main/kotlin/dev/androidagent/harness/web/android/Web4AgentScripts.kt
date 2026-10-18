// SPDX-License-Identifier: Apache-2.0
package dev.androidagent.harness.web.android

internal object Web4AgentScripts {
    private const val ELEMENT_ATTRIBUTE = "data-android-agent-web-id"

    private fun fingerprintFunctions(): String = """
        function harnessClean(value) {
          return String(value || "").replace(/\s+/g, " ").trim();
        }
        function harnessRollingHash(value) {
          var first = 0x811c9dc5;
          var second = 0x9e3779b9;
          for (var index = 0; index < value.length; index++) {
            var code = value.charCodeAt(index);
            first = Math.imul(first ^ code, 0x01000193) >>> 0;
            second = Math.imul(second ^ (code + index), 0x85ebca6b) >>> 0;
          }
          return ("00000000" + first.toString(16)).slice(-8) +
            ("00000000" + second.toString(16)).slice(-8);
        }
        function harnessDocumentMaterial() {
          var root = document.documentElement;
          var html = String(root && root.outerHTML || "");
          var controls = document.querySelectorAll("input,textarea,select");
          var controlState = [];
          for (var controlIndex = 0; controlIndex < controls.length; controlIndex++) {
            var control = controls[controlIndex];
            var controlValue = "";
            try {
              controlValue = String(control.value || "");
            } catch (ignored) {}
            controlState.push([
              String(control.tagName || "").toLowerCase(),
              harnessRollingHash(String(control.getAttribute("id") || "")),
              harnessRollingHash(String(control.getAttribute("name") || "")),
              harnessRollingHash(controlValue) + ":" + controlValue.length,
              control.checked === true ? 1 : 0,
              Number.isFinite(Number(control.selectedIndex)) ? Number(control.selectedIndex) : -1,
              control.disabled === true ? 1 : 0,
              control.readOnly === true ? 1 : 0
            ].join(":"));
          }
          var timeOrigin = 0;
          try {
            timeOrigin = Number(window.performance && window.performance.timeOrigin || 0);
          } catch (ignored) {}
          return JSON.stringify({
            href: harnessRollingHash(String(location.href || "")) + ":" +
              String(location.href || "").length,
            title: harnessRollingHash(String(document.title || "")) + ":" +
              String(document.title || "").length,
            readyState: String(document.readyState || "").slice(0, 32),
            timeOrigin: timeOrigin,
            htmlLength: html.length,
            htmlHash: harnessRollingHash(html),
            controlCount: controls.length,
            controlStateHash: harnessRollingHash(controlState.join("\\u0000"))
          });
        }
        function harnessTargetMaterial(element) {
          if (!element || element.nodeType !== 1) return null;
          var rect = element.getBoundingClientRect();
          var inputType = String(element.getAttribute("type") || "").toLowerCase();
          var criticalNames = [
            "id", "name", "type", "role", "aria-label", "title", "href",
            "src", "action", "formaction", "onclick", "value", "disabled",
            "readonly", "checked", "selected", "multiple", "required",
            "aria-disabled", "aria-checked", "aria-selected", "$ELEMENT_ATTRIBUTE"
          ];
          var critical = {};
          criticalNames.forEach(function(name) {
            var value = element.getAttribute(name);
            if (value !== null) {
              value = String(value);
              critical[name] = harnessRollingHash(value) + ":" + value.length;
            }
          });
          var path = [];
          var current = element;
          for (var depth = 0; current && current.nodeType === 1 && depth < 16; depth++) {
            var tag = String(current.tagName || "").toLowerCase();
            var sibling = current;
            var position = 1;
            while ((sibling = sibling.previousElementSibling)) {
              if (String(sibling.tagName || "").toLowerCase() === tag) position++;
            }
            path.push(tag + ":nth-of-type(" + position + ")");
            current = current.parentElement;
          }
          var targetText = inputType === "password" ? "[REDACTED]" :
            harnessClean(element.innerText || element.textContent || "");
          var targetPath = path.join(">");
          return JSON.stringify({
            tag: String(element.tagName || "").toLowerCase(),
            critical: critical,
            text: harnessRollingHash(targetText) + ":" + targetText.length,
            path: harnessRollingHash(targetPath) + ":" + targetPath.length,
            state: {
              value: harnessRollingHash(String(element.value || "")) + ":" +
                String(element.value || "").length,
              checked: element.checked === true,
              selectedIndex: Number.isFinite(Number(element.selectedIndex)) ?
                Number(element.selectedIndex) : -1,
              disabled: element.disabled === true,
              readOnly: element.readOnly === true
            },
            bounds: [
              Math.round(rect.left), Math.round(rect.top),
              Math.round(rect.width), Math.round(rect.height)
            ]
          });
        }
    """.trimIndent()

    fun observe(
        request: Web4AgentObservationRequest,
        maxOutputChars: Int = 48 * 1024
    ): String = """
        (function() {
          try {
            ${fingerprintFunctions()}
            var maxChars = ${request.maxChars};
            var maxElements = ${request.maxElements};
            var attr = "$ELEMENT_ATTRIBUTE";
            window.__androidAgentWebNextId = window.__androidAgentWebNextId || 1;
            function clean(value) {
              return String(value || "").replace(/\s+/g, " ").trim();
            }
            function visible(element) {
              var style = window.getComputedStyle(element);
              var rect = element.getBoundingClientRect();
              return style.display !== "none" && style.visibility !== "hidden" &&
                Number(style.opacity || 1) !== 0 && rect.width > 0 && rect.height > 0;
            }
            function idFor(element) {
              var existing = element.getAttribute(attr);
              if (existing) return existing;
              var value = "w" + (window.__androidAgentWebNextId++);
              element.setAttribute(attr, value);
              return value;
            }
            function describe(element) {
              var rect = element.getBoundingClientRect();
              var tag = String(element.tagName || "").toLowerCase();
              var inputType = String(element.getAttribute("type") || "").toLowerCase();
              var role = element.getAttribute("role") || "";
              var label = element.getAttribute("aria-label") ||
                element.getAttribute("title") ||
                (element.labels && element.labels[0] && element.labels[0].innerText) || "";
              var text = inputType === "password" ? "[REDACTED]" :
                clean(element.innerText || element.value ||
                  element.getAttribute("placeholder") || "");
              var id = idFor(element);
              return {
                id: id,
                tag: tag,
                role: role,
                type: inputType,
                text: text.slice(0, 500),
                label: clean(label).slice(0, 500),
                selector: "[" + attr + "=\"" + id + "\"]",
                clickable: tag === "a" || tag === "button" || !!element.onclick ||
                  role === "button" || role === "link",
                editable: tag === "input" || tag === "textarea" ||
                  element.isContentEditable === true,
                bounds: {
                  x: Math.round(rect.left),
                  y: Math.round(rect.top),
                  width: Math.round(rect.width),
                  height: Math.round(rect.height)
                },
                __androidAgentTargetMaterial: harnessTargetMaterial(element)
              };
            }
            var candidates = Array.prototype.slice.call(document.querySelectorAll(
              "a,button,input,textarea,select,[role],[contenteditable=true]," +
              "[onclick],[tabindex]"
            )).filter(visible).slice(0, maxElements);
            var hints = [];
            if (document.querySelector("[data-reactroot],[data-reactid]") ||
                window.__REACT_DEVTOOLS_GLOBAL_HOOK__) hints.push("react");
            if (window.Vue || document.querySelector("[data-v-app]")) hints.push("vue");
            if (window.angular || document.querySelector("[ng-version]")) hints.push("angular");
            var bodyText = clean(document.body ? document.body.innerText : "").slice(0, maxChars);
            var payload = {
              ok: true,
              url: String(location.href || ""),
              title: String(document.title || ""),
              readyState: String(document.readyState || ""),
              text: bodyText,
              frameworkHints: hints,
              elements: candidates.map(describe),
              __androidAgentDocumentMaterial: harnessDocumentMaterial()
            };
            var encoded = JSON.stringify(payload);
            if (encoded.length > $maxOutputChars) {
              payload.truncated = true;
              while (payload.elements.length && encoded.length > $maxOutputChars) {
                payload.elements.pop();
                encoded = JSON.stringify(payload);
              }
              if (encoded.length > $maxOutputChars && payload.text) {
                var overflow = encoded.length - $maxOutputChars;
                payload.text = payload.text.slice(
                  0,
                  Math.max(0, payload.text.length - overflow - 64)
                );
                encoded = JSON.stringify(payload);
              }
            }
            if (encoded.length > $maxOutputChars) {
              payload = {
                ok: true,
                truncated: true,
                url: String(location.href || "").slice(0, 256),
                title: String(document.title || "").slice(0, 256),
                readyState: String(document.readyState || "").slice(0, 32),
                text: bodyText.slice(0, Math.max(0, $maxOutputChars - 768)),
                frameworkHints: hints.slice(0, 8),
                elements: [],
                __androidAgentDocumentMaterial: harnessDocumentMaterial()
              };
              encoded = JSON.stringify(payload);
            }
            return encoded.length <= $maxOutputChars ? encoded :
              JSON.stringify({ok:false,error:"observation exceeds configured result limit"});
          } catch (error) {
            return JSON.stringify({
              ok: false,
              error: String(error && (error.stack || error.message) || error).slice(0, 512)
            });
          }
        })()
    """.trimIndent()

    fun read(
        request: Web4AgentReadRequest,
        maxOutputChars: Int = 48 * 1024
    ): String {
        val selector = request.selector?.let(Web4AgentJson::quote) ?: "null"
        val outputLimit = minOf(request.maxChars, maxOutputChars)
        return """
            (function() {
              try {
                var mode = ${Web4AgentJson.quote(request.mode)};
                var selector = $selector;
                var maxChars = ${request.maxChars};
                var root = selector ? document.querySelector(selector) : document;
                if (!root) return JSON.stringify({ok:false,error:"selector did not match"});
                function clean(value) {
                  return String(value || "").replace(/\s+/g, " ").trim();
                }
                function secretField(node) {
                  if (!node || node.nodeType !== 1) return false;
                  var marker = [
                    node.getAttribute("type"),
                    node.getAttribute("name"),
                    node.getAttribute("id"),
                    node.getAttribute("autocomplete")
                  ].join(" ");
                  return /password|passwd|secret|token|api[-_]?key/i.test(marker);
                }
                function safeClone(source) {
                  var clone = source.cloneNode(true);
                  var nodes = [clone].concat(
                    Array.prototype.slice.call(clone.querySelectorAll ?
                      clone.querySelectorAll("*") : [])
                  );
                  nodes.forEach(function(node) {
                    if (!node || node.nodeType !== 1) return;
                    var secret = secretField(node);
                    if (secret) {
                      if ("value" in node) node.value = "[REDACTED]";
                      if (String(node.tagName || "").toLowerCase() === "textarea") {
                        node.textContent = "[REDACTED]";
                      }
                    }
                    Array.prototype.slice.call(node.attributes || []).forEach(
                      function(attribute) {
                        if (secret && attribute.name.toLowerCase() === "value" ||
                            /password|passwd|secret|token|authorization|cookie|api[-_]?key/i
                              .test(attribute.name)) {
                          node.setAttribute(attribute.name, "[REDACTED]");
                        }
                      }
                    );
                  });
                  return clone;
                }
                var value;
                if (mode === "text") {
                  var textSource = root === document ? document.body : root;
                  var safeTextRoot = textSource ? safeClone(textSource) : null;
                  value = clean(safeTextRoot &&
                    (safeTextRoot.innerText || safeTextRoot.textContent) || "")
                    .slice(0, maxChars);
                } else if (mode === "html") {
                  var htmlSource = root === document ? document.documentElement : root;
                  var safeHtmlRoot = safeClone(htmlSource);
                  value = String(safeHtmlRoot.outerHTML || "").slice(0, maxChars);
                } else if (mode === "links") {
                  value = Array.prototype.slice.call(root.querySelectorAll("a[href]"))
                    .slice(0, 100).map(function(link) {
                      return {
                        text: clean(link.innerText || link.getAttribute("aria-label") || "")
                          .slice(0, 500),
                        href: String(link.href || "").slice(0, 2048)
                      };
                    });
                } else if (mode === "forms") {
                  value = Array.prototype.slice.call(root.querySelectorAll("form"))
                    .slice(0, 30).map(function(form) {
                      return {
                        action: String(form.action || "").slice(0, 2048),
                        method: String(form.method || "get").toLowerCase(),
                        fields: Array.prototype.slice.call(form.querySelectorAll(
                          "input,textarea,select,button"
                        )).slice(0, 100).map(function(field) {
                          return {
                            tag: String(field.tagName || "").toLowerCase(),
                            name: field.name || "",
                            type: field.type || "",
                            value: secretField(field) ? "[REDACTED]" :
                              String(field.value || "").slice(0, 500),
                            label: clean(field.getAttribute("aria-label") ||
                              field.getAttribute("placeholder") || "").slice(0, 500)
                          };
                        })
                      };
                    });
                } else if (mode === "tables") {
                  value = Array.prototype.slice.call(root.querySelectorAll("table"))
                    .slice(0, 20).map(function(table) {
                      return clean(table.innerText || "").slice(0, 4000);
                    });
                } else if (mode === "meta") {
                  value = Array.prototype.slice.call(document.querySelectorAll("meta"))
                    .slice(0, 100).map(function(meta) {
                      return {
                        name: meta.name || meta.getAttribute("property") || "",
                        content: String(meta.content || "").slice(0, 2000)
                      };
                    });
                }
                var result = JSON.stringify({ok:true,mode:mode,value:value});
                if (result.length > $outputLimit) {
                  var serialized = typeof value === "string" ? value : JSON.stringify(value);
                  var budget = Math.max(0, $outputLimit - 256);
                  var bounded = String(serialized || "").slice(0, budget);
                  result = JSON.stringify({
                    ok:true,
                    mode:mode,
                    truncated:true,
                    value:bounded
                  });
                  if (result.length > $outputLimit) {
                    var overflow = result.length - $outputLimit;
                    bounded = bounded.slice(0, Math.max(0, bounded.length - overflow - 16));
                    result = JSON.stringify({
                      ok:true,
                      mode:mode,
                      truncated:true,
                      value:bounded
                    });
                  }
                }
                return result.length <= $outputLimit ? result :
                  JSON.stringify({ok:false,error:"read exceeds configured result limit"});
              } catch (error) {
                return JSON.stringify({
                  ok:false,
                  error:String(error && (error.stack || error.message) || error).slice(0, 128)
                });
              }
            })()
        """.trimIndent()
    }

    fun inspect(
        request: Web4AgentInspectRequest,
        maxOutputChars: Int = 48 * 1024
    ): String {
        val selector = request.selector?.let(Web4AgentJson::quote) ?: "null"
        val xpath = request.xpath?.let(Web4AgentJson::quote) ?: "null"
        val text = request.text?.let(Web4AgentJson::quote) ?: "null"
        val outputLimit = minOf(request.maxChars, maxOutputChars)
        return """
            (function() {
              try {
                ${fingerprintFunctions()}
                var selector = $selector;
                var xpath = $xpath;
                var text = $text;
                var maxElements = ${request.maxElements};
                var maxChars = ${request.maxChars};
                var nodes = [];
                if (selector) {
                  nodes = Array.prototype.slice.call(document.querySelectorAll(selector));
                } else if (xpath) {
                  var result = document.evaluate(
                    xpath, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null
                  );
                  for (var i = 0; i < result.snapshotLength; i++) {
                    nodes.push(result.snapshotItem(i));
                  }
                } else {
                  var wanted = String(text || "").toLowerCase();
                  nodes = Array.prototype.slice.call(document.querySelectorAll(
                    "a,button,input,textarea,select,[role],[contenteditable=true]," +
                    "p,li,h1,h2,h3,h4,h5,h6"
                  )).filter(function(node) {
                    var value = String(node.innerText || node.value ||
                      node.getAttribute("aria-label") || "").toLowerCase();
                    return value.indexOf(wanted) >= 0;
                  });
                }
                nodes = nodes.filter(function(node) { return node && node.nodeType === 1; })
                  .slice(0, maxElements);
                function secretField(node) {
                  var marker = [
                    node.getAttribute("type"),
                    node.getAttribute("name"),
                    node.getAttribute("id"),
                    node.getAttribute("autocomplete")
                  ].join(" ");
                  return /password|passwd|secret|token|api[-_]?key/i.test(marker);
                }
                function safeOuterHtml(source) {
                  var clone = source.cloneNode(true);
                  var nodes = [clone].concat(
                    Array.prototype.slice.call(clone.querySelectorAll ?
                      clone.querySelectorAll("*") : [])
                  );
                  nodes.forEach(function(node) {
                    if (!node || node.nodeType !== 1) return;
                    var secret = secretField(node);
                    if (secret) {
                      if ("value" in node) node.value = "[REDACTED]";
                      if (String(node.tagName || "").toLowerCase() === "textarea") {
                        node.textContent = "[REDACTED]";
                      }
                    }
                    Array.prototype.slice.call(node.attributes || []).forEach(
                      function(attribute) {
                        if (secret && attribute.name.toLowerCase() === "value" ||
                            /password|passwd|secret|token|authorization|cookie|api[-_]?key/i
                              .test(attribute.name)) {
                          node.setAttribute(attribute.name, "[REDACTED]");
                        }
                      }
                    );
                  });
                  return String(clone.outerHTML || "");
                }
                var payload = nodes.map(function(node) {
                  var rect = node.getBoundingClientRect();
                  var secret = secretField(node);
                  var attributes = {};
                  Array.prototype.slice.call(node.attributes || []).slice(0, 50)
                    .forEach(function(attribute) {
                      var name = String(attribute.name || "");
                      var value = (secret && name.toLowerCase() === "value") ||
                        /password|passwd|secret|token|authorization|cookie|api[-_]?key/i
                          .test(name) ? "[REDACTED]" :
                        String(attribute.value || "").slice(0, 1000);
                      attributes[name] = value;
                    });
                  return {
                    tag: String(node.tagName || "").toLowerCase(),
                    text: secret ? "[REDACTED]" :
                      String(node.innerText || node.value || "").replace(/\s+/g, " ")
                        .trim().slice(0, 2000),
                    html: safeOuterHtml(node).slice(0, 5000),
                    attributes: attributes,
                    bounds: {
                      x: Math.round(rect.left),
                      y: Math.round(rect.top),
                      width: Math.round(rect.width),
                      height: Math.round(rect.height)
                    },
                    __androidAgentTargetMaterial: harnessTargetMaterial(node)
                  };
                });
                var resultPayload = {
                  ok:true,
                  count:payload.length,
                  elements:payload,
                  __androidAgentDocumentMaterial:harnessDocumentMaterial()
                };
                var encoded = JSON.stringify(resultPayload);
                if (encoded.length > $outputLimit) {
                  resultPayload.truncated = true;
                  while (resultPayload.elements.length && encoded.length > $outputLimit) {
                    resultPayload.elements.pop();
                    encoded = JSON.stringify(resultPayload);
                  }
                }
                return encoded.length <= $outputLimit ? encoded :
                  JSON.stringify({ok:false,error:"inspection exceeds configured result limit"});
              } catch (error) {
                return JSON.stringify({
                  ok:false,
                  error:String(error && (error.stack || error.message) || error).slice(0, 128)
                });
              }
            })()
        """.trimIndent()
    }

    fun evaluate(
        request: Web4AgentEvalRequest,
        maxResultChars: Int,
        expectedDocumentMaterial: String? = null
    ): String {
        val expectedDocument = expectedDocumentMaterial?.let(Web4AgentJson::quote) ?: "null"
        return """
        (function() {
          ${fingerprintFunctions()}
          var source = ${Web4AgentJson.quote(request.script)};
          try {
            var expectedDocument = $expectedDocument;
            if (expectedDocument !== null &&
                harnessDocumentMaterial() !== expectedDocument) {
              return JSON.stringify({
                ok:false,
                code:"STALE_TARGET",
                occurred:false,
                error:"page changed after exact approval"
              });
            }
            var value = (new Function('"use strict";\n' + source))();
            if (typeof value === "undefined") value = null;
            if (typeof value === "bigint") value = String(value);
            if (typeof value === "function" || (value && value.nodeType)) {
              value = String(value);
            }
            var encoded;
            try {
              encoded = JSON.stringify({ok:true,value:value});
            } catch (serializationError) {
              encoded = JSON.stringify({ok:true,value:String(value),serializedAsText:true});
            }
            if (encoded.length > $maxResultChars) {
              var bounded = String(value).slice(0, Math.max(0, $maxResultChars - 256));
              encoded = JSON.stringify({
                ok:true,
                truncated:true,
                value:bounded
              });
              if (encoded.length > $maxResultChars) {
                var overflow = encoded.length - $maxResultChars;
                bounded = bounded.slice(0, Math.max(0, bounded.length - overflow - 16));
                encoded = JSON.stringify({ok:true,truncated:true,value:bounded});
              }
            }
            return encoded.length <= $maxResultChars ? encoded :
              JSON.stringify({ok:false,error:"eval exceeds configured result limit"});
          } catch (error) {
            var message = String(error && (error.stack || error.message) || error);
            return JSON.stringify({
              ok:false,
              error:message.slice(0, Math.max(0, $maxResultChars - 128))
            });
          }
        })()
        """.trimIndent()
    }

    fun action(
        action: Web4AgentAction,
        expectedDocumentMaterial: String? = null,
        expectedTargetMaterial: String? = null
    ): String {
        val elementId = action.elementId?.let(Web4AgentJson::quote) ?: "null"
        val selector = action.selector?.let(Web4AgentJson::quote) ?: "null"
        val xpath = action.xpath?.let(Web4AgentJson::quote) ?: "null"
        val text = action.text?.let(Web4AgentJson::quote) ?: "null"
        val value = action.value?.let(Web4AgentJson::quote) ?: "null"
        val direction = Web4AgentJson.quote(action.direction ?: "down")
        val expectedDocument = expectedDocumentMaterial?.let(Web4AgentJson::quote) ?: "null"
        val expectedTarget = expectedTargetMaterial?.let(Web4AgentJson::quote) ?: "null"
        return """
            (function() {
              try {
                ${fingerprintFunctions()}
                var attr = "$ELEMENT_ATTRIBUTE";
                var elementId = $elementId;
                var selector = $selector;
                var xpath = $xpath;
                var text = $text;
                function find() {
                  if (elementId) {
                    var candidates = document.querySelectorAll("[" + attr + "]");
                    for (var i = 0; i < candidates.length; i++) {
                      if (candidates[i].getAttribute(attr) === elementId) return candidates[i];
                    }
                  }
                  if (selector) return document.querySelector(selector);
                  if (xpath) {
                    return document.evaluate(
                      xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null
                    ).singleNodeValue;
                  }
                  if (text) {
                    var wanted = String(text).toLowerCase();
                    var nodes = document.querySelectorAll(
                      "a,button,input,textarea,select,[role],[contenteditable=true]"
                    );
                    for (var j = 0; j < nodes.length; j++) {
                      var candidate = String(nodes[j].innerText || nodes[j].value ||
                        nodes[j].getAttribute("aria-label") || "").toLowerCase();
                      if (candidate.indexOf(wanted) >= 0) return nodes[j];
                    }
                  }
                  return null;
                }
                var type = ${Web4AgentJson.quote(action.type)};
                var expectedDocument = $expectedDocument;
                var expectedTarget = $expectedTarget;
                if (expectedDocument !== null &&
                    harnessDocumentMaterial() !== expectedDocument) {
                  return JSON.stringify({
                    ok:false,
                    code:"STALE_TARGET",
                    occurred:false,
                    error:"page changed after exact approval"
                  });
                }
                var element = find();
                if (expectedTarget !== null &&
                    harnessTargetMaterial(element) !== expectedTarget) {
                  return JSON.stringify({
                    ok:false,
                    code:"STALE_TARGET",
                    occurred:false,
                    error:"target changed after exact approval"
                  });
                }
                if (type === "scroll") {
                  if (element) {
                    element.scrollIntoView({behavior:"auto",block:"center",inline:"nearest"});
                  } else {
                    var sign = $direction === "up" || $direction === "left" ? -1 : 1;
                    if ($direction === "left" || $direction === "right") {
                      window.scrollBy(sign * ${action.distancePixels}, 0);
                    } else {
                      window.scrollBy(0, sign * ${action.distancePixels});
                    }
                  }
                  return JSON.stringify({ok:true,action:type});
                }
                if (!element) {
                  return JSON.stringify({ok:false,action:type,error:"target not found"});
                }
                if (type === "click") {
                  element.focus();
                  element.click();
                  return JSON.stringify({ok:true,action:type});
                }
                if (type === "type") {
                  var nextValue = $value;
                  element.focus();
                  if (element.isContentEditable) {
                    element.textContent = nextValue;
                  } else {
                    var prototype = Object.getPrototypeOf(element);
                    var descriptor = prototype &&
                      Object.getOwnPropertyDescriptor(prototype, "value");
                    if (descriptor && descriptor.set) descriptor.set.call(element, nextValue);
                    else element.value = nextValue;
                  }
                  element.dispatchEvent(new Event("input", {bubbles:true}));
                  element.dispatchEvent(new Event("change", {bubbles:true}));
                  return JSON.stringify({ok:true,action:type});
                }
                return JSON.stringify({ok:false,action:type,error:"unsupported action"});
              } catch (error) {
                return JSON.stringify({
                  ok:false,
                  error:String(error && (error.stack || error.message) || error).slice(0, 512)
                });
              }
            })()
        """.trimIndent()
    }

    fun guard(expectedDocumentMaterial: String): String = """
        (function() {
          try {
            ${fingerprintFunctions()}
            if (harnessDocumentMaterial() !==
                ${Web4AgentJson.quote(expectedDocumentMaterial)}) {
              return JSON.stringify({
                ok:false,
                code:"STALE_TARGET",
                occurred:false,
                error:"page changed after exact approval"
              });
            }
            return JSON.stringify({ok:true,occurred:false});
          } catch (error) {
            return JSON.stringify({
              ok:false,
              code:"STALE_TARGET",
              occurred:false,
              error:"page fingerprint is unavailable"
            });
          }
        })()
    """.trimIndent()

    fun installEpochObserver(documentToken: String, bridgeName: String): String = """
        (function() {
          try {
            var HostMutationObserver = window.MutationObserver;
            var bridge = window[${Web4AgentJson.quote(bridgeName)}];
            if (!HostMutationObserver || !bridge || !bridge.changed) return "false";
            var observer = new HostMutationObserver(function(records) {
              var meaningful = records.some(function(record) {
                return !(record.type === "attributes" &&
                  record.attributeName === "$ELEMENT_ATTRIBUTE");
              });
              if (meaningful) {
                bridge.changed(${Web4AgentJson.quote(documentToken)});
              }
            });
            observer.observe(document, {
              subtree:true,
              childList:true,
              characterData:true,
              attributes:true
            });
            var notifyStateChange = function() {
              bridge.changed(${Web4AgentJson.quote(documentToken)});
            };
            document.addEventListener("input", notifyStateChange, true);
            document.addEventListener("change", notifyStateChange, true);
            return "true";
          } catch (ignored) {
            return "false";
          }
        })()
    """.trimIndent()

    fun waitPredicate(action: Web4AgentAction): String {
        return when (action.type) {
            "wait_for_selector" -> """
                (function() {
                  try {
                    return document.querySelector(
                      ${Web4AgentJson.quote(action.selector.orEmpty())}
                    ) ? "true" : "false";
                  } catch (error) { return "false"; }
                })()
            """.trimIndent()
            "wait_for_text" -> """
                (function() {
                  var wanted = ${Web4AgentJson.quote(action.text.orEmpty())}.toLowerCase();
                  var actual = String(document.body && document.body.innerText || "")
                    .toLowerCase();
                  return actual.indexOf(wanted) >= 0 ? "true" : "false";
                })()
            """.trimIndent()
            else -> error("Not a wait action: ${action.type}")
        }
    }
}
