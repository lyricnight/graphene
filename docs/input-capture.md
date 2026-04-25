# Input Capture

Graphene does not emulate the browser Pointer Lock API for arbitrary websites. CEF OSR denies native pointer lock, and
synthetic Pointer Lock behavior is fragile across real web games. For pages built for Graphene, use the Graphene input
capture API instead.

## Capture the cursor and Escape key

```js
const capture = await grapheneBridge.input.capture({
  cursor: true,
  escape: "release"
});

capture.onMove(({ movementX, movementY }) => {
  camera.rotate(movementX, movementY);
});

capture.onRelease(({ reason }) => {
  showPauseMenu(reason);
});
```

When `cursor` is `true`, Graphene hides and grabs the Minecraft GLFW cursor and sends relative movement through
`capture.onMove`. Movement is unbounded and does not depend on `document.pointerLockElement`.

Request capture from a user interaction, such as a click inside the web view. Graphene releases capture if the web view
loses focus.

When `escape` is `"release"`, Escape is first delivered to the browser and then releases the capture without closing the
Minecraft screen. Pressing Escape again uses normal Minecraft screen behavior.

## Release capture

```js
await capture.release();
```

You can also release without keeping the capture handle:

```js
await grapheneBridge.input.release();
```

Graphene also releases capture automatically when the surface navigates, closes, or loses focus.

## Escape modes

```js
await grapheneBridge.input.capture({ escape: "release" });
await grapheneBridge.input.capture({ escape: "passthrough" });
await grapheneBridge.input.capture({ escape: "minecraft" });
```

- `"release"` or `"browser-first"`: forward Escape to the browser, release capture, and prevent Minecraft screen close.
- `"passthrough"`: forward Escape to the browser and keep capture active.
- `"minecraft"`: do not capture Escape.
- `true`: alias for `"release"`.
- `false`: alias for `"minecraft"`.

## Capture state

```js
const unsubscribe = grapheneBridge.input.onCaptureChange(state => {
  console.log(state.active, state.cursor, state.escape);
});

console.log(grapheneBridge.input.isCaptured());
console.log(grapheneBridge.input.state());

unsubscribe();
```

The state object has this shape:

```ts
type GrapheneInputCaptureState = {
  active: boolean;
  cursor: boolean;
  escape: "release" | "passthrough" | "minecraft";
};
```

## Why not Pointer Lock?

CEF supports pointer-lock permissions as a concept, but CEF OSR currently denies pointer lock in the renderer host view.
Graphene therefore exposes an explicit bridge contract instead of pretending to implement browser Pointer Lock for all
websites.
