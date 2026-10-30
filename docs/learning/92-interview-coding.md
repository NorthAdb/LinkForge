# 面试手撕题卡

21 张高频题卡，覆盖双指针、滑动窗口、哈希、栈、链表、二叉树、BFS/DFS、堆、动态规划、二分十类题型。本文和 [14-algorithms.md](14-algorithms.md) 的分工：14 按题型讲"怎么想"，每类一个带推导的完整题解；本文是冲刺期的速查卡，每张卡只有五个部分：题面、两级提示、复杂度（带来源）、完整参考实现、常见失败点。提示不给答案，推导卡住时回 14 对应节看完推导，再回来限时重写。

每张卡按固定顺序过：

1. 只读题面，掐表 25 分钟，按 14 的五步流程写：复述题意、问数据规模、说暴力解和复杂度、优化并写代码、走例子报复杂度。
2. 卡住看提示一（方向），想 5 分钟。再卡住看提示二（关键一步）。两级都试过才允许看下一节。
3. 写完对照参考实现。实现是面试现场写得出的清晰版，不是最优化炫技版；每个都是自包含的 Java 文件，复制一个代码块就能编译运行（`javac --release 17 -encoding UTF-8 文件名.java && java 文件名`），text 块里的输出全部来自真实运行。
4. 对照常见失败点自查，差异处就是知识缺口。隔 3 天重做（[93-glossary-review.md](93-glossary-review.md) 的节奏），连续两遍 25 分钟内全对才移出轮换。

复杂度的报法全篇统一：「时间 O(n log n)，来自排序；空间 O(n)，来自哈希表」。只报阶不报来源，被追问一句"为什么"就接不住。语言基础见 [java/10-01-types-and-references.md](java/10-01-types-and-references.md)；`HashMap`、`ArrayDeque`、`PriorityQueue` 的原理见 [11-java-collections.md](11-java-collections.md)。

## 卡 1 两数之和（LC 1）

题型：哈希表。同题限时版，方法详解见 [14-algorithms.md](14-algorithms.md) 第 3 节。

**题面**。给定整数数组 `nums` 和整数 `target`，返回和为 `target` 的两个元素的下标。每个输入恰好有一组解，同一个元素不能用两次。例：`{1,3,4,6}` 加 `target = 7` 返回 `[1,2]`（3 加 4）。数组可能有负数和重复值。

### 提示

- 提示一（方向）：题目反复在问"`target - nums[i]` 出现过吗，在下标几"。逐对比较是 O(n²)，把它变成一次查询，用什么结构？
- 提示二（关键一步）：一遍遍历，哈希表存"值到下标"。遍历到 `i` 时先查 `target - nums[i]`，查不到才把 `nums[i]` 存进去。先查后存这个顺序，就是"自己不能配自己"。

### 参考实现（自己写完再看）

```java
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class TwoSum {
    // LeetCode 1: 返回和为 target 的两个下标，恰有一组解
    public static int[] twoSum(int[] nums, int target) {
        Map<Integer, Integer> seen = new HashMap<>();   // 值 -> 下标
        for (int i = 0; i < nums.length; i++) {
            Integer j = seen.get(target - nums[i]);     // 先查后存：自己不在表里，不会和自己配对
            if (j != null) return new int[]{j, i};
            seen.put(nums[i], i);
        }
        throw new IllegalArgumentException("no answer");
    }

    public static void main(String[] args) {
        System.out.println(Arrays.toString(twoSum(new int[]{1, 3, 4, 6}, 7)));
        System.out.println(Arrays.toString(twoSum(new int[]{3, 3}, 6)));
        System.out.println(Arrays.toString(twoSum(new int[]{-1, -2, -3, -4}, -7)));
    }
}
```

```text
输出：
[1, 2]
[0, 1]
[2, 3]
```

第二行是重复值用例：处理第二个 3 之前第一个 3 已在表里，所以能配对；处理第一个 3 时表是空的，它不会和自己配对。第三行验证负数。

**复杂度**。时间 O(n)，来自一次遍历，每步一次哈希查找；空间 O(n)，来自哈希表最多 n 个条目。哈希查找说"均摊 O(1)"而不是"严格 O(1)"：`HashMap` 扩容要把旧条目全部搬家，单次最坏不止常数，摊到 n 次 `put` 上才是常数（见 [11-java-collections.md](11-java-collections.md)）。

**常见失败点**。

- 先存后查：当前元素先进表，`target` 恰是它的两倍时会和自己配对。`{3,2,4}` 加 `target = 6` 会错误地把下标 0 用两次。
- `seen.get(...)` 用 `int` 接返回值：键不存在时是 `null`，拆箱直接抛 `NullPointerException`。用 `Integer` 接住再判空。
- 复杂度报错方式：说成"哈希查找严格 O(1)"。正确说法是均摊 O(1)；Java 8 之后单个桶过长还会树化，单次最坏 O(log n)。

**追问**。排序加双指针可做到 O(1) 额外空间，但要处理下标还原；本题要的就是下标，哈希更直接。

## 卡 2 有效括号（LC 20）

题型：栈。同题限时版，方法详解见 [14-algorithms.md](14-algorithms.md) 第 4 节。

**题面**。输入只含 `()[]{}` 的字符串，判断是否有效：左括号必须用同类型的右括号闭合，顺序正确，不允许交叉。例：`"{[()]}"` 有效；`"([)]"` 无效；`"("` 无效。

### 提示

- 提示一（方向）：想清楚 `"([)]"` 为什么错：第一个右括号必须匹配"最近的那个还没闭合的左括号"。"最近"对应什么处理顺序？
- 提示二（关键一步）：遇到左括号，把期望的右括号压栈；遇到右括号，先判栈空再弹栈比对。扫完后栈必须为空，否则有左括号没闭合。

### 参考实现（自己写完再看）

```java
import java.util.ArrayDeque;

public class ValidParentheses {
    // LeetCode 20: 括号串是否有效
    public static boolean isValid(String s) {
        ArrayDeque<Character> stack = new ArrayDeque<>();
        for (char c : s.toCharArray()) {
            if (c == '(') stack.push(')');   // 压入期望的闭合符，比对时省一次映射
            else if (c == '[') stack.push(']');
            else if (c == '{') stack.push('}');
            else if (stack.isEmpty() || stack.pop() != c) return false;
        }
        return stack.isEmpty();   // 左括号有剩余也无效
    }

    public static void main(String[] args) {
        System.out.println(isValid("{[()]}"));
        System.out.println(isValid("([)]"));
        System.out.println(isValid("(("));
        System.out.println(isValid("]"));
        System.out.println(isValid(""));
    }
}
```

```text
输出：
true
false
false
false
true
```

`"]"` 靠 `isEmpty()` 判断兜住；`""` 空串合法，循环一次不跑，栈天然为空。

**复杂度**。时间 O(n)，每个字符至多入栈、出栈各一次；空间 O(n)，最坏全是左括号，全部压栈。

**常见失败点**。

- 栈空时直接 `pop()`：抛 `java.util.NoSuchElementException`。右括号分支必须先判 `isEmpty()`。
- 只数左右括号的个数不看顺序：`"([)]"` 数量正好匹配，但它是交叉，无效。
- 忘了结尾的 `stack.isEmpty()`：`"(("` 这种左括号剩余的串误判有效。

## 卡 3 反转链表（LC 206）

题型：链表。同题限时版，方法详解见 [14-algorithms.md](14-algorithms.md) 第 5 节。

**题面**。给定单链表头节点 `head`，反转链表并返回新头。要求原地改指针，不开新链。例：`10->20->30` 变成 `30->20->10`；空链表和单节点原样返回。

### 提示

- 提示一（方向）：链表只能单向走。一旦把 `curr.next` 改掉，后面的节点就找不到了，动手前要留一个备份。
- 提示二（关键一步）：三个引用各管一段：`prev` 是已反转部分的头，`curr` 是待处理节点，`next` 是后继备份。循环不变量：`prev` 开头的链永远已反转。

### 参考实现（自己写完再看）

```java
public class ReverseList {
    // LeetCode 206: 原地反转单链表
    static class ListNode {
        int val;
        ListNode next;
        ListNode(int val) { this.val = val; }
    }

    public static ListNode reverseList(ListNode head) {
        ListNode prev = null;            // 已反转部分的头
        ListNode curr = head;            // 待处理节点
        while (curr != null) {
            ListNode next = curr.next;   // 先备份后继，改完指向还能找到后面
            curr.next = prev;
            prev = curr;
            curr = next;
        }
        return prev;
    }

    static ListNode build(int... vals) {
        ListNode dummy = new ListNode(0);
        ListNode tail = dummy;
        for (int v : vals) { tail.next = new ListNode(v); tail = tail.next; }
        return dummy.next;
    }

    static String show(ListNode head) {
        StringBuilder sb = new StringBuilder("[");
        for (ListNode p = head; p != null; p = p.next) {
            sb.append(p.val);
            if (p.next != null) sb.append(", ");
        }
        return sb.append("]").toString();
    }

    public static void main(String[] args) {
        System.out.println(show(reverseList(build(10, 20, 30))));
        System.out.println(show(reverseList(build(7))));
        System.out.println(show(reverseList(null)));
    }
}
```

```text
输出：
[30, 20, 10]
[7]
[]
```

后两行是边界：单节点反转还是自己，空链表反转还是空。

**复杂度**。时间 O(n)，每个节点一次指针改写；空间 O(1)，只有三个引用。

**常见失败点**。

- 不备份 `next` 就写 `curr.next = prev`：后半段从改动点断开，永久丢失。链表的单向性让"先存后断"成为硬性顺序。
- 返回值写成 `curr`：循环退出时 `curr` 是 `null`，应返回 `prev`。
- 循环条件写成 `curr.next != null`：空链表直接空指针，最后一个节点也没被处理。

**追问**。递归版时间同阶但空间 O(n)，来自调用栈；反转部分区间（LC 92）是进阶，哑结点（见卡 15）能消掉头节点的特判。

## 卡 4 无重复字符的最长子串（LC 3）

题型：滑动窗口。同题限时版，方法详解见 [14-algorithms.md](14-algorithms.md) 第 2 节；14 的实现用"字符最后位置"跳跃，本卡换成收缩式窗口。

**题面**。给定字符串 `s`，返回不含重复字符的最长子串的长度。例：`"abcabcbb"` 是 3（`abc`）；`"dvdf"` 是 3（`vdf`，容易错算成 2）；`""` 是 0。

### 提示

- 提示一（方向）：要的是"连续"的一段，且"无重复"这个性质随长度变化。滑动窗口：右端扩一格，性质被破坏时左端收缩。
- 提示二（关键一步）：窗口里维护一个计数数组 `int[128]`。新字符进窗口后如果计数超过 1，左端收缩并给移出的字符减计数，直到重复消除。

### 参考实现（自己写完再看）

```java
public class LongestSubstring {
    // LeetCode 3: 无重复字符的最长子串长度（收缩式滑动窗口）
    public static int lengthOfLongestSubstring(String s) {
        int[] count = new int[128];      // 窗口内每个字符的个数
        int best = 0, left = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            count[c]++;
            while (count[c] > 1) {       // 新字符重复：左端收缩到挤掉多余的 c
                count[s.charAt(left)]--; // 移出窗口的字符必须减计数
                left++;
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }

    public static void main(String[] args) {
        System.out.println(lengthOfLongestSubstring("abcabcbb"));
        System.out.println(lengthOfLongestSubstring(""));
        System.out.println(lengthOfLongestSubstring("bbbb"));
        System.out.println(lengthOfLongestSubstring("dvdf"));
    }
}
```

```text
输出：
3
0
1
3
```

`"dvdf"` 是收缩窗口的经典陷阱：右端第二次遇到 `d` 时，左端必须收缩到第一个 `d` 之后，窗口是 `vd`，再扩 `f` 得 3。减计数漏了就会停在 2。

**复杂度**。时间 O(n)，`right` 走 n 步，`left` 只前进不后退，合计最多 2n 次移动；空间 O(1)，计数数组固定 128 格，与输入长度无关。

**常见失败点**。

- 收缩时忘了给移出的字符减计数：计数只增不减，窗口假性饱和，`"dvdf"` 错算成 2。
- 用 Map 存"字符最后出现位置"的版本里，跳跃时忘和 `left` 取大：左指针跳回重复区内，产生假的合法窗口。
- 复杂度报错方式：看到 for 加 while 两层就报 O(n²)。两个指针各自最多走 n 步，是 O(n)。

**追问**。至多包含 K 个不同字符（LC 340）窗口性质同构：把"计数超过 1"换成"不同字符数超过 K"。

## 卡 5 合并区间（LC 56）

题型：排序加线性扫描，与 [14-algorithms.md](14-algorithms.md) 第 1 节"排序消掉无序性"的思路同源。

**题面**。输入区间数组 `intervals`，每个元素是 `{起点, 终点}`，合并所有重叠的区间。相接也算重叠：`[1,4]` 和 `[4,5]` 合并成 `[1,5]`。例：`{{1,3},{2,6},{8,10},{15,18}}` 合并成 `{{1,6},{8,10},{15,18}}`。

### 提示

- 提示一（方向）：重叠判断本身只要比较两个数，前提是区间按什么顺序排？先排序。
- 提示二（关键一步）：按起点排序后逐个处理：当前区间的起点不超过"结果里最后一个区间的终点"就合并，合并时终点取两者较大的那个。

### 参考实现（自己写完再看）

```java
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MergeIntervals {
    // LeetCode 56: 合并所有重叠的区间
    public static int[][] merge(int[][] intervals) {
        Arrays.sort(intervals, Comparator.comparingInt(a -> a[0]));   // 按起点排序
        List<int[]> out = new ArrayList<>();
        for (int[] iv : intervals) {
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && iv[0] <= last[1]) {   // 起点不超过上一区间的终点，重叠（相接也算）
                last[1] = Math.max(last[1], iv[1]);
            } else {
                out.add(new int[]{iv[0], iv[1]});
            }
        }
        return out.toArray(new int[0][]);
    }

    public static void main(String[] args) {
        System.out.println(Arrays.deepToString(merge(new int[][]{{1, 3}, {2, 6}, {8, 10}, {15, 18}})));
        System.out.println(Arrays.deepToString(merge(new int[][]{{1, 4}, {4, 5}})));
        System.out.println(Arrays.deepToString(merge(new int[][]{{5, 7}, {1, 3}})));
    }
}
```

```text
输出：
[[1, 6], [8, 10], [15, 18]]
[[1, 5]]
[[1, 3], [5, 7]]
```

第二行验证相接合并；第三行验证乱序输入，排序后正常。

**复杂度**。时间 O(n log n)，来自排序；后面的扫描是 O(n)，不占主导。空间 O(n)，来自结果列表；`Arrays.sort` 的对象数组版本用 TimSort（一种归并排序的变体），自身还要 O(n) 辅助空间。

**常见失败点**。

- 重叠写成 `iv[0] < last[1]`：相接的 `[1,4]` 和 `[4,5]` 漏合并，本题相接要合并。
- 合并时直接 `last[1] = iv[1]`：`{1,10}` 后跟 `{2,3}` 会被截短成 `[1,3]`，必须 `Math.max`。
- 复杂度只报扫描 O(n)，漏了排序的 O(n log n)。排序才是主导项。

## 卡 6 数组第 K 大（LC 215）

题型：堆。同题限时版，方法详解见 [14-algorithms.md](14-algorithms.md) 第 8 节。

**题面**。给定整数数组 `nums` 和整数 `k`，返回第 K 大的元素。是排序后的第 K 个，不是第 K 个不同的值。例：`{3,1,4,2}` 第 2 大是 3；`{7,7,7}` 第 1 大是 7；`{5,-1,3}` 第 3 大是 -1。

### 提示

- 提示一（方向）：答案不需要全排序，只需要随时拿到"目前最大的 K 个数里最小的那个"。什么结构取最值最便宜？
- 提示二（关键一步）：小顶堆装前 K 个，之后每个新元素和堆顶比较：不大于堆顶就丢弃，大于就弹出堆顶、自己入堆。任意时刻堆顶就是第 K 大。

### 参考实现（自己写完再看）

```java
import java.util.PriorityQueue;

public class KthLargest {
    // LeetCode 215: 第 K 大（排序后第 K 个，允许重复值）
    public static int findKthLargest(int[] nums, int k) {
        PriorityQueue<Integer> heap = new PriorityQueue<>();   // 小顶堆：堆顶是前 K 大里的最小者
        for (int x : nums) {
            heap.offer(x);
            if (heap.size() > k) heap.poll();   // 超过 K 就弹掉门槛外的最小者
        }
        return heap.peek();
    }

    public static void main(String[] args) {
        System.out.println(findKthLargest(new int[]{3, 1, 4, 2}, 2));
        System.out.println(findKthLargest(new int[]{7, 7, 7}, 1));
        System.out.println(findKthLargest(new int[]{5, -1, 3}, 3));
    }
}
```

```text
输出：
3
7
-1
```

第二个用例验证重复值不算去重；第三个验证 k 等于 n 时答案是最小值。

**复杂度**。时间 O(n log k)，n 个元素各一次入堆加至多一次出堆，堆里最多 k 个元素、堆高 log k；空间 O(k)，来自堆。对照排序 O(n log n)：k 远小于 n 时，大量低于门槛的元素只花常数成本。

**常见失败点**。

- 求第 K 大误用大顶堆：要 O(n) 空间，还得弹 K-1 次才见答案；小顶堆随时可答。
- 弹出条件写成 `heap.size() >= k`：第 K 大自己会被弹掉，`{3,1,4,2}` 加 `k = 2` 会错答 4。是 `> k` 才弹。
- 以为堆内元素有序：堆只保证父子关系，兄弟之间无序，要有序输出得逐个 poll。

**追问**。海量数据流场景答堆，理由是内存有界 O(k)；口述快速选择（每轮 partition 期望淘汰一半，平均 O(n)、最坏 O(n²)）作为备选。

## 卡 7 岛屿数量（LC 200）

题型：BFS/DFS。同题限时版，方法详解见 [14-algorithms.md](14-algorithms.md) 第 7 节；14 用 DFS 沉岛，本卡换成 BFS。

**题面**。`'1'`（陆地）和 `'0'`（水）组成的二维网格，数岛屿数量。岛由上下左右相邻的陆地组成，斜角不算。例：`{{'1','1','0'},{'1','0','0'},{'0','0','1'}}` 有 2 个岛。

### 提示

- 提示一（方向）：从一个没访问过的陆地格出发，上下左右能走到的所有陆地格恰好是一个完整的岛。数完一个就把整个岛标记掉，后面不会重复数。
- 提示二（关键一步）：BFS 版：起点先标记再入队；出队时看四个方向的邻居，邻居是陆地就同样"先标记再入队"。标记在入队前做，不在出队后做。

### 参考实现（自己写完再看）

```java
import java.util.ArrayDeque;
import java.util.Queue;

public class NumIslandsBFS {
    // LeetCode 200: 网格岛屿数量（BFS 沉岛）
    public static int numIslands(char[][] grid) {
        int count = 0;
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[0].length; c++) {
                if (grid[r][c] == '1') {
                    count++;
                    sink(grid, r, c);
                }
            }
        }
        return count;
    }

    static void sink(char[][] grid, int r0, int c0) {
        Queue<int[]> queue = new ArrayDeque<>();
        grid[r0][c0] = '0';              // 入队前就标记，防止同一格重复入队
        queue.offer(new int[]{r0, c0});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] d : dirs) {
                int r = cell[0] + d[0], c = cell[1] + d[1];
                if (r >= 0 && r < grid.length && c >= 0 && c < grid[0].length && grid[r][c] == '1') {
                    grid[r][c] = '0';
                    queue.offer(new int[]{r, c});
                }
            }
        }
    }

    public static void main(String[] args) {
        char[][] g1 = {
            {'1', '1', '0'},
            {'1', '0', '0'},
            {'0', '0', '1'}
        };
        System.out.println(numIslands(g1));
        System.out.println(numIslands(new char[][]{{'0', '0'}, {'0', '0'}}));
        System.out.println(numIslands(new char[][]{{'1'}}));
    }
}
```

```text
输出：
2
0
1
```

三行分别是常规网格、全水、单格陆地。

**复杂度**。时间 O(mn)，每个格子至多入队一次；空间 O(mn)，最坏全图是陆地，队列装下整个网格（DFS 版则是递归栈深到 mn）。

**常见失败点**。

- 标记时机错：出队时才标记，相邻的陆地格会被四个邻居重复入队。入队前标记是硬规矩。
- 方向数组只写右和下两个方向：S 形、凹形的岛会漏数。四个方向一个不能少。
- 边界检查漏写或写错：行的 `r < 0 || r >= grid.length` 和列的判断成对出现，漏一边就数组越界。

**追问**。多源 BFS（LC 994 腐烂橘子）：所有源点先全部入队再逐层扩散，层数就是时间。多源和单源只差队列的初始内容。

## 卡 8 旋转数组搜索（LC 33）

题型：二分。标准二分（LC 704）的循环不变量推导见 [14-algorithms.md](14-algorithms.md) 第 10 节，本卡的实现是升级版 LC 33。

**题面**。升序数组在某个未知下标被旋转过，例如 `[0,1,2,4,5,6,7]` 旋转成 `[4,5,6,7,0,1,2]`，在其中找 `target`，返回下标，不存在返回 -1，要求 O(log n)。

### 提示

- 提示一（方向）：旋转数组从中点切开，`[lo, mid]` 和 `[mid, hi]` 至少一半是有序的。先判断哪半有序，再看 `target` 落不落在那半的取值范围内。
- 提示二（关键一步）：循环不变量沿袭标准二分：答案若存在必在 `[lo, hi]`。左半有序的判据用 `nums[lo] <= nums[mid]`，等号必须给；`mid` 写成 `lo + (hi - lo) / 2`。

### 参考实现（自己写完再看）

```java
public class RotateSearch {
    // LeetCode 33: 旋转升序数组里找 target
    public static int search(int[] nums, int target) {
        int lo = 0, hi = nums.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] == target) return mid;
            if (nums[lo] <= nums[mid]) {                       // 左半 [lo, mid] 有序
                if (nums[lo] <= target && target < nums[mid]) hi = mid - 1;
                else lo = mid + 1;
            } else {                                           // 右半 [mid, hi] 有序
                if (nums[mid] < target && target <= nums[hi]) lo = mid + 1;
                else hi = mid - 1;
            }
        }
        return -1;
    }

    public static void main(String[] args) {
        System.out.println(search(new int[]{4, 5, 6, 7, 0, 1, 2}, 0));
        System.out.println(search(new int[]{4, 5, 6, 7, 0, 1, 2}, 3));
        System.out.println(search(new int[]{5, 1, 3}, 3));
        System.out.println(search(new int[]{1}, 1));
    }
}
```

```text
输出：
4
-1
2
0
```

第三行是跨旋转点的用例：中点落在有序的右半，靠范围判断走进左半。第四行是单元素边界。

**复杂度**。时间 O(log n)，每轮至少排除一半；空间 O(1)，三个下标变量。

**常见失败点**。

- 有序判据写成 `nums[lo] < nums[mid]`：`lo == mid` 时左半只剩一个元素，`[3,1]` 找 1 会错答 -1。等号就是给单元素区间用的。
- `mid` 写成 `(lo + hi) / 2`：两个下标都很大时相加溢出成负数，先减后加不溢出。
- 范围判断漏等号：边界上的元素就查不到。写之前想清楚"是否可能相等"。

## 卡 9 打家劫舍（LC 198）

题型：动态规划。同题限时版，方法详解（含暴力递归的指数爆炸实测）见 [14-algorithms.md](14-algorithms.md) 第 9 节。

**题面**。一排房子，`nums[i]` 是第 i 户的金额。不能偷相邻两家，求能偷到的最高金额。例：`{2,1,1,2}` 偷第 0 和第 3 家共 4；`{1,3,1,3,1}` 偷两户 3 共 6。

### 提示

- 提示一（方向）：对第 i 户只有两个决策。偷，收益是前 i-2 户的最优加本户；不偷，收益是前 i-1 户的最优。取大。
- 提示二（关键一步）：转移只依赖前两项，两个变量滚动就够。滚动顺序：先算出 `cur`，再把 `prev1` 挪到 `prev2`、`cur` 挪到 `prev1`。

### 参考实现（自己写完再看）

```java
public class HouseRobber {
    // LeetCode 198: 不偷相邻两家，求最大金额（滚动变量）
    public static int rob(int[] nums) {
        int prev2 = 0, prev1 = 0;    // prev2 = 前 i-2 户最优，prev1 = 前 i-1 户最优
        for (int x : nums) {
            int cur = Math.max(prev1, prev2 + x);   // 不偷本户 vs 偷本户
            prev2 = prev1;
            prev1 = cur;
        }
        return prev1;
    }

    public static void main(String[] args) {
        System.out.println(rob(new int[]{2, 1, 1, 2}));
        System.out.println(rob(new int[]{1, 3, 1, 3, 1}));
        System.out.println(rob(new int[]{1}));
        System.out.println(rob(new int[]{}));
    }
}
```

```text
输出：
4
6
1
0
```

第二行说明"跳着拿大额"胜过"拿满小额"；后两行是单户和空输入。

**复杂度**。时间 O(n)，每户一次转移；空间 O(1)，两个滚动变量。不压缩的 `dp` 数组版本是 O(n) 空间。

**常见失败点**。

- 滚动顺序写反：先 `prev2 = prev1` 再算 `cur`，"偷本户"那一项用的是前 i-1 户的最优，结果偏大且可能违反不相邻约束。
- 状态含义含糊：`dp[i]` 是"前 i 户最优"还是"必须偷第 i 户"，两种定义的转移不同。先把定义写成一句中文，再写转移。
- 复杂度报错方式：把暴力递归的指数级（调用次数按斐波那契节奏增长）当成 DP 的复杂度。DP 版是 O(n)。

**追问**。环形版（LC 213）：首尾相邻，拆成"含首不含尾"和"含尾不含首"两个线性问题分别求，取大。

## 卡 10 每日温度（LC 739）

题型：单调栈。14 第 4 节的延伸片段就是本题，这里给完整可运行版，见 [14-algorithms.md](14-algorithms.md) 第 4 节。

**题面**。给定每日温度数组 `t`，对每一天算"要等几天才会遇到更暖的一天"，等不到填 0。更暖是严格大于。例：`{73,74,75,71,69,72,76,73}` 得 `{1,1,4,2,1,1,0,0}`；`{30,30,30}` 全 0。

### 提示

- 提示一（方向）：每个下标的答案是"右边第一个更大值的位置"到它的距离。单调栈（栈内对应的值保持单调，某个元素被弹出时它的答案恰好确定）一次扫完。
- 提示二（关键一步）：栈存下标，温度从栈底到栈顶递减。新温度比栈顶高，弹出栈顶并写答案 `i - j`；新温度不更高，自己入栈。

### 参考实现（自己写完再看）

```java
import java.util.ArrayDeque;
import java.util.Arrays;

public class DailyTemperatures {
    // LeetCode 739: 等到更暖一天要几天（单调栈）
    public static int[] dailyTemperatures(int[] t) {
        int[] answer = new int[t.length];
        ArrayDeque<Integer> stack = new ArrayDeque<>();   // 存下标，温度从栈底到栈顶递减
        for (int i = 0; i < t.length; i++) {
            while (!stack.isEmpty() && t[i] > t[stack.peek()]) {
                int j = stack.pop();      // 下标 j 等到了第一个更暖的日子 i
                answer[j] = i - j;
            }
            stack.push(i);
        }
        return answer;                    // 留在栈里的永远等不到，默认 0
    }

    public static void main(String[] args) {
        System.out.println(Arrays.toString(dailyTemperatures(new int[]{73, 74, 75, 71, 69, 72, 76, 73})));
        System.out.println(Arrays.toString(dailyTemperatures(new int[]{5, 4, 3, 2, 1})));
        System.out.println(Arrays.toString(dailyTemperatures(new int[]{30, 30, 30})));
    }
}
```

```text
输出：
[1, 1, 4, 2, 1, 1, 0, 0]
[0, 0, 0, 0, 0]
[0, 0, 0]
```

第二行全递减，谁都等不到；第三行验证"更暖"是严格大于。

**复杂度**。时间 O(n)，每个下标至多入栈、出栈各一次，按均摊计数；空间 O(n)，最坏温度递减时栈全满。

**常见失败点**。

- 弹栈条件写成 `>=`：相等温度不算更暖，`{30,30}` 会错把等待天数填成 1。
- 栈里存温度值不存下标：答案要的是距离，存值就算不出 `i - j`。
- 复杂度报错方式：看到 while 嵌套在 for 里就报 O(n²)。每个下标进出栈各至多一次，总数有界，是 O(n)。

**追问**。接雨水（LC 42）是单调栈的另一形态，本卡集的卡 19 用对撞指针解它。

## 卡 11 LRU 缓存（LC 146）

题型：设计题，哈希查找见 [14-algorithms.md](14-algorithms.md) 第 3 节，链表指针操作见第 5 节，双向链表细节见 [11-java-collections.md](11-java-collections.md)。

**题面**。设计 LRU（Least Recently Used，最久未使用）缓存：容量上限 `capacity`；`get(key)` 返回值并把该 key 记为最近使用，未命中返回 -1；`put(key, value)` 写入该 key 也记为最近使用；容量满时淘汰最久未使用的 key。get 和 put 都要 O(1)。

### 提示

- 提示一（方向）：O(1) 查值是哈希表的活，O(1) 淘汰最久未用是链表的活。两个结构组合，各干一件事。
- 提示二（关键一步）：哈希表存 key 到链表节点；双向链表带头尾哨兵（不存数据的假节点，让"链表空""删头尾"不再特判），最近使用的挂头，最久未用的在尾。摘接一个节点是四次指针赋值，先在纸上画再写。

### 参考实现（自己写完再看）

```java
import java.util.HashMap;
import java.util.Map;

public class LRUCache {
    // LeetCode 146: get/put 均 O(1) 的 LRU 缓存（哈希表 + 双向链表）
    private final int capacity;
    private final Map<Integer, Node> map = new HashMap<>();   // key -> 链表节点
    private final Node head = new Node(0, 0);   // 哨兵：head.next 是最近使用的节点
    private final Node tail = new Node(0, 0);   // 哨兵：tail.prev 是最久未用的节点

    private static class Node {
        int key, val;
        Node prev, next;
        Node(int key, int val) { this.key = key; this.val = val; }
    }

    public LRUCache(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    public int get(int key) {
        Node n = map.get(key);
        if (n == null) return -1;
        moveToFront(n);
        return n.val;
    }

    public void put(int key, int value) {
        Node n = map.get(key);
        if (n != null) {                 // 覆盖已有 key：改值并且移动到队头
            n.val = value;
            moveToFront(n);
            return;
        }
        if (map.size() == capacity) {    // 满了先淘汰最久未用
            Node lru = tail.prev;
            unlink(lru);
            map.remove(lru.key);         // 链表和哈希表要一起删
        }
        Node fresh = new Node(key, value);
        map.put(key, fresh);
        linkFront(fresh);
    }

    private void unlink(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void linkFront(Node n) { n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }
    private void moveToFront(Node n) { unlink(n); linkFront(n); }

    public static void main(String[] args) {
        LRUCache c = new LRUCache(2);
        c.put(1, 1);
        c.put(2, 2);
        System.out.println(c.get(1));   // 1 被使用，变成最近
        c.put(3, 3);                    // 满，淘汰最久未用的 2
        System.out.println(c.get(2));
        c.put(4, 4);                    // 淘汰 1
        System.out.println(c.get(1));
        System.out.println(c.get(3));
        System.out.println(c.get(4));

        LRUCache one = new LRUCache(1);
        one.put(1, 1);
        one.put(1, 2);                  // 覆盖已有 key，不触发淘汰
        System.out.println(one.get(1));
    }
}
```

```text
输出：
1
-1
-1
3
4
2
```

前六行是 LC 146 的官方用例：get(1) 之后 1 变成最近，所以淘汰的是 2。最后一行验证覆盖已有 key 不触发淘汰。

**复杂度**。get 和 put 都是 O(1)：一次哈希定位加常数次指针摘接；空间 O(capacity)，哈希表和链表各存 capacity 个节点。

**常见失败点**。

- put 覆盖已有 key 时只改值不移动节点：它不算"最近使用"，之后可能被误淘汰。覆盖也要移动到队头。
- 淘汰时只删链表节点，忘了 `map.remove(lru.key)`：哈希表里留着幽灵节点，get 还能查到。
- 摘接指针顺序写错导致断链：先接新邻居再断旧指针。裸写首尾判断更容易错，哨兵就是为此存在的。

**追问**。库版三行能写出来：`LinkedHashMap` 构造时传 `accessOrder = true`，重写 `removeEldestEntry`。面试先给手写版证明懂原理，再对比成熟缓存库（容量、过期、统计都齐），是加分顺序。

## 卡 12 爬楼梯（LC 70）

题型：动态规划。14 第 9 节把它作为打家劫舍的同型递推提到，见 [14-algorithms.md](14-algorithms.md) 第 9 节。

**题面**。爬楼梯，每次跨 1 或 2 阶，n 阶有几种走法。例：n=3 有 3 种（1+1+1、1+2、2+1）。

### 提示

- 提示一（方向）：想最后一步。要么从第 n-1 阶跨 1 阶上来，要么从第 n-2 阶跨 2 阶上来，两条路互斥且完备。所以 f(n) = f(n-1) + f(n-2)。
- 提示二（关键一步）：递推只依赖前两项，两个变量滚动，别开数组。初值 `f(0) = 1`（原地不动算一种）、`f(1) = 1`。

### 参考实现（自己写完再看）

```java
public class ClimbStairs {
    // LeetCode 70: 每次爬 1 或 2 阶，n 阶的走法数
    public static int climbStairs(int n) {
        int prev = 1, cur = 1;       // f(0)=1（站在原地算一种），f(1)=1
        for (int i = 2; i <= n; i++) {
            int next = prev + cur;   // 最后一步跨 1 阶有 f(i-1) 种，跨 2 阶有 f(i-2) 种
            prev = cur;
            cur = next;
        }
        return cur;
    }

    public static void main(String[] args) {
        System.out.println(climbStairs(1));
        System.out.println(climbStairs(2));
        System.out.println(climbStairs(3));
        System.out.println(climbStairs(10));
        System.out.println(climbStairs(45));   // int 上限内最大的 n
    }
}
```

```text
输出：
1
2
3
89
1836311903
```

最后一行 1836311903 是 `f(45)`，再往上一项 `f(46) = 2971215073` 就超过 int 上限 2147483647，相加溢出成负数。

**复杂度**。时间 O(n)，n-1 次加法；空间 O(1)。

**常见失败点**。

- 初值错位：把 `f(1)` 和 `f(2)` 都写成 1，n=2 就错答 1。正确是 1 和 2。
- 用没记忆化的递归：调用次数按斐波那契节奏指数增长，n 稍大就跑不完，复杂度约 O(1.618 的 n 次方)，不是 O(n)。
- 忽视溢出：`f(45)` 已是 int 装得下的最大项。题目没给 n 的范围时先问。

**追问**。为什么是斐波那契：最后一步的两种拆分正好对应 f(n-1) 和 f(n-2)。为什么能 DP：无后效性，之后怎么走只取决于现在站在哪阶，不取决于之前怎么走过来的。

## 卡 13 三数之和（LC 15）

题型：双指针。对撞指针的基础推导见 [14-algorithms.md](14-algorithms.md) 第 1 节，本题是它的进阶应用。

**题面**。给定整数数组 `nums`，找出所有和为 0 的三元组，三元组之间不能重复（同一组值只输出一次）。例：`{-1,0,1,2,-1,-4}` 得 `[[-1,-1,2],[-1,0,1]]`；少于 3 个元素输出空。

### 提示

- 提示一（方向）：排序后固定一个数，剩下两个数在右侧区间用对撞指针夹逼。排序让"跳过重复值"和"移动方向"都有依据。
- 提示二（关键一步）：去重有三处：固定位跳过与前一个相同的值；命中一组后，左右指针各自跳过相同值，再同时收缩。

### 参考实现（自己写完再看）

```java
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ThreeSum {
    // LeetCode 15: 所有不重复的三元组，和为 0
    public static List<List<Integer>> threeSum(int[] nums) {
        Arrays.sort(nums);
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < nums.length - 2; i++) {
            if (i > 0 && nums[i] == nums[i - 1]) continue;   // 固定位跳重：保留每组重复值的第一次出现
            int lo = i + 1, hi = nums.length - 1;
            while (lo < hi) {
                int sum = nums[i] + nums[lo] + nums[hi];
                if (sum < 0) lo++;
                else if (sum > 0) hi--;
                else {
                    out.add(Arrays.asList(nums[i], nums[lo], nums[hi]));
                    while (lo < hi && nums[lo] == nums[lo + 1]) lo++;   // 左指针跳过相同值
                    while (lo < hi && nums[hi] == nums[hi - 1]) hi--;   // 右指针跳过相同值
                    lo++;
                    hi--;
                }
            }
        }
        return out;
    }

    public static void main(String[] args) {
        System.out.println(threeSum(new int[]{-1, 0, 1, 2, -1, -4}));
        System.out.println(threeSum(new int[]{0, 0, 0, 0}));
        System.out.println(threeSum(new int[]{1, 2}));
    }
}
```

```text
输出：
[[-1, -1, 2], [-1, 0, 1]]
[[0, 0, 0]]
[]
```

第二行验证含重复元素时只输出一组 `[0,0,0]`，全靠两处跳重。

**复杂度**。时间 O(n²)，排序的 O(n log n) 不占主导，主项是固定位 n 层乘每层夹逼至多 O(n)；空间 O(1)，不计输出和排序的辅助空间。

**常见失败点**。

- 固定位跳重写成 `nums[i] == nums[i + 1]`：每组重复值的第一次出现也被跳掉，`[-1,-1,2]` 这类含重复元素的解直接漏。判"和前一个相同"，保留第一次出现。
- 命中后只走一步不跳重：`{-2,0,0,2,2}` 会输出重复的 `[-2,0,2]`。
- 忘排序：夹逼和跳重全部失去前提，结果又乱又重。

## 卡 14 环形链表 II（LC 142）

题型：链表快慢指针，套路见 [14-algorithms.md](14-algorithms.md) 第 5 节，本题是它的进阶。

**题面**。给定链表头节点，判断是否有环；有环返回环的入口节点，无环返回 null。要求 O(1) 空间，不能用 `HashSet` 记访问过的节点。例：`3->2->0->-4` 尾节点指向下标 1 的节点，入口是值 2 的节点。

### 提示

- 提示一（方向）：快慢指针：慢的走一步，快的走两步，有环必相遇，因为快指针每轮把差距缩小 1。相遇只说明有环，相遇点不是入口。
- 提示二（关键一步）：相遇后，一个指针放回头部，两个指针同速前进，再相遇处就是入口。为什么成立：设头到入口 a 步、入口到相遇点 b 步、环长 c，用"快指针路程是慢指针的两倍"推 a 和 (c-b) 的关系。

### 参考实现（自己写完再看）

```java
public class DetectCycle {
    // LeetCode 142: 找环的入口节点，无环返回 null
    static class ListNode {
        int val;
        ListNode next;
        ListNode(int val) { this.val = val; }
    }

    public static ListNode detectCycle(ListNode head) {
        ListNode slow = head, fast = head;
        while (fast != null && fast.next != null) {   // 两层判空，缺一层会空指针
            slow = slow.next;
            fast = fast.next.next;
            if (slow == fast) {                       // 相遇：一个指针从头，同速再走
                ListNode p = head;
                while (p != slow) { p = p.next; slow = slow.next; }
                return slow;
            }
        }
        return null;
    }

    static ListNode build(int[] vals, int pos) {      // pos = 尾节点指向的下标，-1 表示无环
        ListNode dummy = new ListNode(0);
        ListNode tail = dummy;
        ListNode cycleStart = null;
        for (int i = 0; i < vals.length; i++) {
            tail.next = new ListNode(vals[i]);
            tail = tail.next;
            if (i == pos) cycleStart = tail;
        }
        if (cycleStart != null) tail.next = cycleStart;
        return dummy.next;
    }

    static String describe(ListNode node) {
        return node == null ? "no cycle" : "tail connects at value " + node.val;
    }

    public static void main(String[] args) {
        System.out.println(describe(detectCycle(build(new int[]{3, 2, 0, -4}, 1))));
        System.out.println(describe(detectCycle(build(new int[]{1, 2}, 0))));
        System.out.println(describe(detectCycle(build(new int[]{1, 2, 3}, -1))));
    }
}
```

```text
输出：
tail connects at value 2
tail connects at value 1
no cycle
```

第二行是环入口在头节点的情形；第三行无环。

**复杂度**。时间 O(n)：慢指针进环一圈内必与快指针相遇，回头重走最多再走 a 步，合计线性；空间 O(1)。

**常见失败点**。

- 判空只判一层：`fast != null` 和 `fast.next != null` 两层都要判，偶数长度链快指针会两步跨过结尾，漏判一层就空指针。
- 误把相遇点当入口返回：相遇点在环内，位置随环长变化，必须回头重走。
- 用 `val` 相等代替引用相等：判相遇和找入口比的都是 `==`（同一个节点），链表节点值可以重复。

## 卡 15 合并两个有序链表（LC 21）

题型：链表。哑结点套路见 [14-algorithms.md](14-algorithms.md) 第 5 节。

**题面**。合并两条升序链表成一条升序链表，返回新头。例：`1->2->4` 和 `1->3->4` 合并成 `1->1->2->3->4->4`；两条都空返回空。

### 提示

- 提示一（方向）：每次比较两条链的当前节点，小的接到结果尾部。结果链的头会变，用什么结构省掉"结果头是谁"的特判？
- 提示二（关键一步）：哑结点（挂在真正的头前面的假节点）：`dummy.next` 就是结果头；一条链耗尽后，另一条剩余整段直接接上。

### 参考实现（自己写完再看）

```java
public class MergeTwoLists {
    // LeetCode 21: 合并两条升序链表（哑结点 + 尾插）
    static class ListNode {
        int val;
        ListNode next;
        ListNode(int val) { this.val = val; }
    }

    public static ListNode mergeTwoLists(ListNode a, ListNode b) {
        ListNode dummy = new ListNode(0);   // 哑结点：头节点不用特判
        ListNode tail = dummy;
        while (a != null && b != null) {
            if (a.val <= b.val) { tail.next = a; a = a.next; }
            else { tail.next = b; b = b.next; }
            tail = tail.next;
        }
        tail.next = (a == null) ? b : a;    // 一条耗尽，另一条剩余整段接上
        return dummy.next;
    }

    static ListNode build(int... vals) {
        ListNode dummy = new ListNode(0);
        ListNode tail = dummy;
        for (int v : vals) { tail.next = new ListNode(v); tail = tail.next; }
        return dummy.next;
    }

    static String show(ListNode head) {
        StringBuilder sb = new StringBuilder("[");
        for (ListNode p = head; p != null; p = p.next) {
            sb.append(p.val);
            if (p.next != null) sb.append(", ");
        }
        return sb.append("]").toString();
    }

    public static void main(String[] args) {
        System.out.println(show(mergeTwoLists(build(1, 2, 4), build(1, 3, 4))));
        System.out.println(show(mergeTwoLists(null, build(0))));
        System.out.println(show(mergeTwoLists(build(5), null)));
        System.out.println(show(mergeTwoLists(null, null)));
    }
}
```

```text
输出：
[1, 1, 2, 3, 4, 4]
[0]
[5]
[]
```

后三行是空链的三种组合，哑结点让它们不需要任何特判。

**复杂度**。时间 O(m+n)，两个链表每个节点恰好接一次；空间 O(1)，哑结点加尾指针。

**常见失败点**。

- 接完节点忘了前进 `a = a.next`：同一节点被反复比较，死循环。
- 剩余部分忘了接，或硬编码接 `a`：另一条还有尾巴时整段丢失。三目一行兜住。
- 用递归版时没意识到栈深：链表十万级长度时递归栈溢出，迭代版无此问题。

**追问**。合并 K 条（LC 23）：两两合并 O(kN)，或小顶堆 O(N log k)，堆里始终放 K 条链的当前节点。

## 卡 16 全排列（LC 46）

题型：回溯。递归三要素见 [14-algorithms.md](14-algorithms.md) 第 6 节；回溯（走一条路，走到头就退回来换一条）是"选择、递归、撤销"的树形递归。

**题面**。给定不含重复数字的数组 `nums`，返回所有全排列。例：`{1,2,3}` 有 6 个排列，顺序不限但要全。

### 提示

- 提示一（方向）：把排列想象成在一棵树上走路径：第 1 层选第 1 个数，第 2 层从剩下没用过的数里选，走到叶子收集一条路径。树有多深？每层怎么选？
- 提示二（关键一步）：回溯三件套：选择（`used[i] = true` 加路径加入）、递归、撤销（路径弹出加 `used[i] = false`）。选择和撤销必须严格成对。收集结果时拷贝路径。

### 参考实现（自己写完再看）

```java
import java.util.ArrayList;
import java.util.List;

public class Permute {
    // LeetCode 46: 全排列（回溯三件套：选择 - 递归 - 撤销）
    public static List<List<Integer>> permute(int[] nums) {
        List<List<Integer>> out = new ArrayList<>();
        backtrack(nums, new boolean[nums.length], new ArrayList<>(), out);
        return out;
    }

    static void backtrack(int[] nums, boolean[] used, List<Integer> path, List<List<Integer>> out) {
        if (path.size() == nums.length) {
            out.add(new ArrayList<>(path));   // 必须拷贝：path 还要继续被改
            return;
        }
        for (int i = 0; i < nums.length; i++) {
            if (used[i]) continue;
            used[i] = true;                   // 选择
            path.add(nums[i]);
            backtrack(nums, used, path, out); // 递归
            path.remove(path.size() - 1);     // 撤销：和"选择"严格成对
            used[i] = false;
        }
    }

    public static void main(String[] args) {
        System.out.println(permute(new int[]{1, 2, 3}));
        System.out.println(permute(new int[]{0, 1}));
        System.out.println(permute(new int[]{7}));
    }
}
```

```text
输出：
[[1, 2, 3], [1, 3, 2], [2, 1, 3], [2, 3, 1], [3, 1, 2], [3, 2, 1]]
[[0, 1], [1, 0]]
[[7]]
```

**复杂度**。时间 O(n · n!)，n! 个排列，每个收集时拷贝 O(n)；空间 O(n)，递归深度加 `used` 加路径，不计输出。

**常见失败点**。

- 撤销漏一行：`used` 复位和路径弹出缺任何一个，兄弟分支被污染，结果缺失或重复。
- 收集时直接 `out.add(path)`：加进去的是同一个引用，递归继续改它，最后全是同一份。必须 `new ArrayList<>(path)`。
- 复杂度只报 n!：漏了拷贝的 n 因子，完整是 O(n · n!)。输出本身就有 n! 个排列，这是复杂度降不下去的原因。

**追问**。含重复元素的版本（LC 47）：先排序，同层跳过与前一个相同且未被使用的数。

## 卡 17 最长递增子序列（LC 300）

题型：动态规划加二分。DP 五步见 [14-algorithms.md](14-algorithms.md) 第 9 节，二分写法见第 10 节。

**题面**。给定整数数组 `nums`，求最长严格递增子序列的长度。子序列可以跳着选，相对顺序不变。例：`{10,9,2,5,3,7,101,18}` 是 4（比如 `2,3,7,18`）；`{7,7,7,7}` 是 1，严格递增，相等不算。

### 提示

- 提示一（方向）：先写直接的定义。`dp[i]` 是以 `nums[i]` 结尾的最长递增子序列长度，从前面每个比它小的位置转移，取大加一。答案取所有 `dp[i]` 的最大值。
- 提示二（关键一步）：O(n log n) 版维护 `tails`：长度 k+1 的递增子序列的最小尾值。它严格递增，所以每个新元素能用二分找到它该待的位置。

### 参考实现（自己写完再看）

```java
public class LongestIncreasing {
    // LeetCode 300: 最长递增子序列长度，O(n^2) DP 与 O(n log n) 贪心二分两版
    public static int lengthByDp(int[] nums) {
        if (nums.length == 0) return 0;
        int[] dp = new int[nums.length];   // dp[i] = 以 nums[i] 结尾的最长递增子序列长度
        int best = 0;
        for (int i = 0; i < nums.length; i++) {
            dp[i] = 1;
            for (int j = 0; j < i; j++) {
                if (nums[j] < nums[i]) dp[i] = Math.max(dp[i], dp[j] + 1);
            }
            best = Math.max(best, dp[i]);
        }
        return best;
    }

    public static int lengthByBinary(int[] nums) {
        int[] tails = new int[nums.length];   // tails[k] = 长度 k+1 的递增子序列的最小尾值
        int size = 0;
        for (int x : nums) {
            int lo = 0, hi = size;            // 在 tails[0..size) 里找第一个 >= x 的位置
            while (lo < hi) {
                int mid = lo + (hi - lo) / 2;
                if (tails[mid] < x) lo = mid + 1;
                else hi = mid;
            }
            tails[lo] = x;                    // 覆盖或追加，tails 始终严格递增
            if (lo == size) size++;
        }
        return size;
    }

    public static void main(String[] args) {
        int[][] cases = {{10, 9, 2, 5, 3, 7, 101, 18}, {0, 1, 0, 3, 2, 3}, {7, 7, 7, 7}};
        for (int[] nums : cases) {
            System.out.println(lengthByDp(nums) + " / " + lengthByBinary(nums));
        }
    }
}
```

```text
输出：
4 / 4
4 / 4
1 / 1
```

斜杠前是 DP 版，后面是二分版，两版结果一致。第二行是二分版的好例子：`0,1,0,3,2,3` 里第二次出现的 `0` 会覆盖 `tails[0]`，长度不变。

**复杂度**。DP 版时间 O(n²)，来自双重循环；二分版 O(n log n)，每个元素一次 O(log n) 二分。空间都是 O(n)，来自 `dp` 或 `tails` 数组。两版都要会说，先 DP 后二分。

**常见失败点**。

- DP 转移写成 `nums[j] <= nums[i]`：相等元素串了起来，`{7,7,7,7}` 错答 4。严格递增用 `<`。
- 把 `tails` 数组当成 LIS 本身输出：它是"每个长度的最小尾值"，内容不是子序列，`size` 才是答案。
- 复杂度混报：报哪个版本就说哪个的来源，别把 O(n log n) 安到双重循环头上。

## 卡 18 字符串解码（LC 394）

题型：栈。栈处理嵌套结构的思路见 [14-algorithms.md](14-algorithms.md) 第 4 节。

**题面**。编码规则 `k[encoded_string]` 表示方括号内的串重复 k 次，k 是正整数，可以嵌套。解码。例：`"3[a2[c]]"` 得 `"accaccacc"`；`"12[a]"` 得 12 个 a；无括号的 `"abc"` 原样输出。

### 提示

- 提示一（方向）：遇到 `[`，之前的解码进度要"挂起"；遇到 `]`，把挂起的进度取回来拼接。挂起、恢复，是什么结构？
- 提示二（关键一步）：两个栈。数字栈存重复次数，字符串栈存 `[` 之前的进度；`]` 弹出后拼 repeat 份当前串，再把它恢复为当前串。多位数逐位累计 `num = num * 10 + (c - '0')`。

### 参考实现（自己写完再看）

```java
import java.util.ArrayDeque;
import java.util.Deque;

public class DecodeString {
    // LeetCode 394: 3[a2[c]] -> accaccacc（数字栈 + 字符串栈）
    public static String decodeString(String s) {
        Deque<Integer> countStack = new ArrayDeque<>();
        Deque<StringBuilder> strStack = new ArrayDeque<>();
        StringBuilder cur = new StringBuilder();
        int num = 0;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                num = num * 10 + (c - '0');   // 多位数累计：12[a] 不能只读一位
            } else if (c == '[') {
                countStack.push(num);         // 当前进度和重复次数挂起
                strStack.push(cur);
                num = 0;
                cur = new StringBuilder();
            } else if (c == ']') {
                int repeat = countStack.pop();
                StringBuilder prefix = strStack.pop();
                for (int i = 0; i < repeat; i++) prefix.append(cur);
                cur = prefix;                 // 拼好的结果恢复为当前串
            } else {
                cur.append(c);
            }
        }
        return cur.toString();
    }

    public static void main(String[] args) {
        System.out.println(decodeString("3[a2[c]]"));
        System.out.println(decodeString("2[abc]3[cd]ef"));
        System.out.println(decodeString("12[a]"));
        System.out.println(decodeString("abc"));
    }
}
```

```text
输出：
accaccacc
abcabccdcdcdef
aaaaaaaaaaaa
abc
```

第三行验证多位数：`12` 要累计两位。第四行验证无括号的原样输出。

**复杂度**。时间 O(n + L)，L 是解码结果的总长度，来自逐字符拼接；最坏情况 L 与 n·maxK 同量级。空间 O(n + L)，两个栈加当前串。

**常见失败点**。

- 多位数只读一位：`"12[a]"` 被当成 `2[a]` 解码成 `aa`。数字要累计，不是读到就压栈。
- `]` 处理完忘了 `cur = prefix`：拼好的结果没有恢复为当前串，后续字符全丢。
- 用 String 加号拼接：每次拼接复制整串，复杂度退化到 O(L²)。用 `StringBuilder`。

## 卡 19 接雨水（LC 42）

题型：双指针。对撞指针见 [14-algorithms.md](14-algorithms.md) 第 1 节。

**题面**。给定柱子高度数组 `height`（非负整数），柱宽 1，下雨后能接多少水。例：`{0,1,0,2,1,0,1,3,2,1,2,1}` 接 6；`{4,2,0,3,2,5}` 接 9。

### 提示

- 提示一（方向）：每个位置的水位由它左右两侧最大高度的较小者决定。前缀最大加后缀最大两个数组能做；对撞指针能把空间压到 O(1)。
- 提示二（关键一步）：左右指针加两侧最大值。哪边高度小，哪边的水位就能确定（对面必有不低于它的墙），结算短板一侧并移动短板指针。

### 参考实现（自己写完再看）

```java
public class TrappingRainWater {
    // LeetCode 42: 柱状图接水量（对撞双指针）
    public static int trap(int[] height) {
        int left = 0, right = height.length - 1;
        int leftMax = 0, rightMax = 0, water = 0;
        while (left < right) {
            if (height[left] < height[right]) {
                // 短板在左边：这一格的水位由 leftMax 决定，右边必有不低于它的墙
                if (height[left] >= leftMax) leftMax = height[left];
                else water += leftMax - height[left];
                left++;
            } else {
                if (height[right] >= rightMax) rightMax = height[right];
                else water += rightMax - height[right];
                right--;
            }
        }
        return water;
    }

    public static void main(String[] args) {
        System.out.println(trap(new int[]{0, 1, 0, 2, 1, 0, 1, 3, 2, 1, 2, 1}));
        System.out.println(trap(new int[]{4, 2, 0, 3, 2, 5}));
        System.out.println(trap(new int[]{1, 2, 3, 4}));
        System.out.println(trap(new int[]{3, 3, 3}));
    }
}
```

```text
输出：
6
9
0
0
```

后两行是单调递增和全相等，都接不到水。

**复杂度**。时间 O(n)，左右指针合计走 n 步，每格结算一次；空间 O(1)，两个指针加两个最大值。前缀最大数组版时间同阶、空间 O(n)。

**常见失败点**。

- 结算方向写反：在长板一侧结算。水位由短板决定，永远结算 `height[left] < height[right]` 时的左边那一侧。
- 两侧最大值的更新漏掉"遇到更大先更新"的分支：用 0 起步，`if (height[left] >= leftMax)` 先更新，否则加水量，这样最不易错。
- 复杂度混报：双指针 O(n)/O(1)，单调栈 O(n)/O(n)，前缀数组 O(n)/O(n)，报的时候版本要对上。

## 卡 20 最小栈（LC 155）

题型：栈的设计，见 [14-algorithms.md](14-algorithms.md) 第 4 节。

**题面**。设计一个栈，支持 push、pop、top（看栈顶）、getMin（取栈内最小值），四个操作都要 O(1)。

### 提示

- 提示一（方向）：min 会因为 pop 回退：弹掉最小值后要知道次小。只存一个 min 变量不够，把"历史最小值"叠成序列。
- 提示二（关键一步）：辅助栈。push 时把"当前最小值"也压进辅助栈，相等也压，记两份；pop 时弹出值等于辅助栈顶就一起弹。

### 参考实现（自己写完再看）

```java
import java.util.ArrayDeque;
import java.util.Deque;

public class MinStack {
    // LeetCode 155: push/pop/top/getMin 全 O(1)（辅助栈存"当前最小值"）
    private final Deque<Integer> stack = new ArrayDeque<>();
    private final Deque<Integer> mins = new ArrayDeque<>();

    public void push(int x) {
        stack.push(x);
        if (mins.isEmpty() || x <= mins.peek()) mins.push(x);   // 相等也压：记两份
    }

    public void pop() {
        int v = stack.pop();          // int 接住，避免 Integer 之间的 == 比较
        if (v == mins.peek()) mins.pop();
    }

    public int top() { return stack.peek(); }

    public int getMin() { return mins.peek(); }

    public static void main(String[] args) {
        MinStack s = new MinStack();
        s.push(-2);
        s.push(0);
        s.push(-3);
        System.out.println(s.getMin());   // -3
        s.pop();
        System.out.println(s.top());      // 0
        System.out.println(s.getMin());   // -2

        MinStack dup = new MinStack();
        dup.push(1);
        dup.push(1);
        dup.pop();
        System.out.println(dup.getMin()); // 相等最小值弹掉一份，另一份还在
    }
}
```

```text
输出：
-3
0
-2
1
```

前三行是 LC 官方操作序列；最后一行专门打"相等最小值"这个坑。

**复杂度**。四个操作全 O(1)：push 多一次比较和至多一次压栈；空间 O(n)，辅助栈最坏和主栈一样大（每次都压进更小值时）。

**常见失败点**。

- 辅助栈压入条件写成 `<`：`push(1)` 两次后 `pop` 一次，最小值记录被弹光，getMin 对着还剩一个 1 的栈答错。相等要再压一份。
- pop 里用 `Integer` 接住再和 `mins.peek()` 做 `==`：两个 `Integer` 的 `==` 比的是引用，-128 到 127 之外装箱对象不相等。用 `int` 接住触发拆箱，比的是值。
- 复杂度报错：说 getMin 要遍历 O(n)，那是没做这题的答案；辅助栈正是拿 O(n) 空间换来的 O(1)。

## 卡 21 翻转二叉树（LC 226）

题型：二叉树。递归三要素见 [14-algorithms.md](14-algorithms.md) 第 6 节。

**题面**。给定二叉树根节点，翻转成镜像：每个节点的左右子树整体互换。例：层序 `[4,2,7,1,3,6,9]` 翻成 `[4,7,2,9,6,3,1]`。

### 提示

- 提示一（方向）：树的递归三要素：终止条件是什么？本层做什么？向父层返回什么？
- 提示二（关键一步）：终止条件 `root == null` 返回 null。本层先递归拿到左右子树翻好后的新根，两个都存下来，再交换挂接。顺序反了，一侧的输入就被另一侧覆盖。

### 参考实现（自己写完再看）

```java
import java.util.ArrayDeque;
import java.util.Queue;

public class InvertTree {
    // LeetCode 226: 翻转二叉树（左右子树整体镜像）
    static class TreeNode {
        int val;
        TreeNode left, right;
        TreeNode(int val) { this.val = val; }
    }

    public static TreeNode invertTree(TreeNode root) {
        if (root == null) return null;            // 三要素一：终止条件，空树返回空
        TreeNode left = invertTree(root.left);    // 先接住两个递归结果
        TreeNode right = invertTree(root.right);
        root.left = right;                        // 再交换：顺序反了会丢子树
        root.right = left;
        return root;
    }

    static String levelVals(TreeNode root) {      // 层序打印用于验证
        if (root == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        Queue<TreeNode> q = new ArrayDeque<>();
        q.offer(root);
        while (!q.isEmpty()) {
            TreeNode n = q.poll();
            sb.append(n.val);
            if (n.left != null) q.offer(n.left);
            if (n.right != null) q.offer(n.right);
            if (!q.isEmpty()) sb.append(", ");
        }
        return sb.append("]").toString();
    }

    public static void main(String[] args) {
        TreeNode root = new TreeNode(4);
        root.left = new TreeNode(2);
        root.right = new TreeNode(7);
        root.left.left = new TreeNode(1);
        root.left.right = new TreeNode(3);
        root.right.left = new TreeNode(6);
        root.right.right = new TreeNode(9);
        System.out.println(levelVals(root));
        System.out.println(levelVals(invertTree(root)));
        System.out.println(levelVals(invertTree(null)));
        System.out.println(levelVals(invertTree(new TreeNode(5))));
    }
}
```

```text
输出：
[4, 2, 7, 1, 3, 6, 9]
[4, 7, 2, 9, 6, 3, 1]
[]
[5]
```

第一行是原树，第二行是镜像；后两行是空树和单节点。

**复杂度**。时间 O(n)，每个节点访问一次；空间 O(h)，h 是树高，来自递归调用栈，链状树最坏 O(n)。

**常见失败点**。

- 不存递归结果直接交换：先写 `root.left = invertTree(root.right)`，`root.right` 引用没变但随后又对它递归，两边都拿到翻过的版本。两个结果先存后换。
- 终止条件写成叶子（左右都为 null）：空树没有叶子，直接空指针。树递归的最小情形永远是"空"。
- 以为翻转是交换值：交换的是左右子树的引用，改的是结构。值交换不改变结构，子树一深就露馅。

## 手撕现场清单（写代码前 60 秒做的事）

1. 复述题意与约束，问清数据规模。规模决定解法档次，主动问是加分项。
2. 先说暴力解和它的复杂度，再说优化。暴力暴露重复劳动在哪里，优化就是消灭它，展示演进比直接背最优解可信。
3. 边界三连：空输入？单元素？极值或全相同？每张卡的用例就是按这三类挑的。
4. 写完主动走一个例子，报复杂度并说来源：「时间 O(n log n)，来自排序；空间 O(n)，来自结果列表」。

## 自测

每条先自己回答，再看答案。

1. 问：卡 6 报复杂度说"时间 O(log n)"，错在哪？
   答：漏了 n。n 个元素各入堆一次，每次堆操作 O(log k)，总时间 O(n log k)；k 是堆的大小上限，不是 n。空间 O(k) 同理，来自堆本身。
2. 问：卡 4 的窗口收缩时，为什么要把移出去的字符计数减一？
   答：计数数组描述的是"当前窗口里有什么"。不减一，窗口明明移走了字符，计数还记着，新字符会被误判成重复，窗口被错误压缩，`"dvdf"` 这类用例答案偏小。
3. 问：卡 13 固定位跳重为什么判 `nums[i] == nums[i-1]` 而不是 `nums[i] == nums[i+1]`？
   答：目的是"每组重复值只作为固定位用一次"。判前一个保留每组重复值的第一次出现，从它出发能找到含重复元素的解（如排序后的 `[-1,-1,2]`）；判后一个会把第一次出现也跳掉，漏解。
4. 问：卡 14 相遇后，为什么一个指针放回头部、两指针同速前进就一定在入口相遇？
   答：设头到入口 a、入口到相遇点 b、环长 c。相遇时快指针路程是慢指针的两倍：a+b 加 n 圈等于 2(a+b)，化简得 a = (n-1)·c + (c-b)。c-b 正是从相遇点走回入口的距离，所以头部指针走 a 步到入口时，相遇点指针也恰好走完 (c-b) 加若干整圈，同在入口。
5. 问：卡 20 辅助栈的压入条件为什么是 `<=` 不是 `<`？
   答：压入的是"当前最小值"，相等的最小值出现两次时要记两份。用 `<`，弹出其中一个相等值时辅助栈把记录弹掉了，另一个还在主栈里，getMin 却已经回退，答案错。
6. 问：卡 11 的 put 覆盖已存在的 key 时，为什么除了改值还要把节点移到队头？
   答：get 和 put 都算一次"使用"，淘汰的是最久未用的节点。只改值不移动，这个 key 的"最后使用时间"还是旧的，可能被误淘汰。
