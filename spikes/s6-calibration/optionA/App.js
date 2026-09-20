import { useState } from 'react';
import { StyleSheet, Text, View, Button } from 'react-native';

// The S6 reference app for Option A, matched to the Option B and D hellos: a label, a
// button, and a counter. Nothing else, so the size and startup numbers compare.
export default function App() {
  const [count, setCount] = useState(0);
  return (
    <View style={styles.container} onLayout={onFirstLayout}>
      <Text style={styles.label}>Count: {count}</Text>
      <Button title="Increment" onPress={() => setCount((c) => c + 1)} />
    </View>
  );
}

let reported = false;
// onLayout fires once the view has been measured and positioned, which is the closest
// RN equivalent to "the first frame is committed" that the S6 harness pairs with its
// pre-launch host timestamp.
function onFirstLayout() {
  if (reported) return;
  reported = true;
  console.log(`S6_FIRST_RENDER ${Date.now() / 1000}`);
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#fff', alignItems: 'center', justifyContent: 'center' },
  label: { fontSize: 20, marginBottom: 20 },
});
