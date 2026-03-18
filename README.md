# @capacitor/spatial-vision

this plugin is going to measure a distance between two object using user mobile camera.

## Install

To use npm

```bash
npm install @capacitor/spatial-vision
````

To use yarn

```bash
yarn add @capacitor/spatial-vision
```

Sync native files

```bash
npx cap sync
```

## API

<docgen-index>

* [`echo(...)`](#echo)
* [`startCamera(...)`](#startcamera)
* [Interfaces](#interfaces)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### echo(...)

```typescript
echo(options: { value: string; }) => Promise<{ value: string; }>
```

| Param         | Type                            |
| ------------- | ------------------------------- |
| **`options`** | <code>{ value: string; }</code> |

**Returns:** <code>Promise&lt;{ value: string; }&gt;</code>

--------------------


### startCamera(...)

```typescript
startCamera(options: MeasuringInputs) => Promise<{ status: string; message: string; }>
```

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#measuringinputs">MeasuringInputs</a></code> |

**Returns:** <code>Promise&lt;{ status: string; message: string; }&gt;</code>

--------------------


### Interfaces


#### MeasuringInputs

| Prop        | Type                |
| ----------- | ------------------- |
| **`title`** | <code>string</code> |

</docgen-api>
