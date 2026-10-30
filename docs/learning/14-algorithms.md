# 算法训练：题型方法与完整题解

面试手撕考的不是聪明，是流程。背过答案的人，被追问一句"为什么这样移动指针"就露底；会推导的人，换个数字也能现场重来一遍。这份文档按十类高频题型组织。每类给你四样东西：识别特征（什么样的题属于这类）、通用套路（固定的思考路径）、为什么这样想（套路的推导过程）、一个完整题解（题面、暴力解、优化推导、可运行代码、复杂度来源）。

每个题解都是自包含的 Java 文件，复制一个代码块就能编译运行，输出全部来自真实运行。文末单独一节把三个题型接回 LinkForge 的真实代码：布隆过滤器、滑动窗口限流、随机短码。算法题和项目代码分开讲，不互相硬凑。

准备知识：数组、引用、基本语法见 [java/10-01-types-and-references.md](java/10-01-types-and-references.md)；本文用到的 `HashMap`、`ArrayDeque`、`PriorityQueue` 的原理见 [11-java-collections.md](11-java-collections.md)。

## 手撕题的五步流程

面试现场，一道算法题的完整动作是固定的，按顺序做完五步：

1. 复述题意。确认输入类型、取值范围、要不要返回全部解。
2. 问数据规模。n 是 10 还是 10 万，决定暴力解能不能过，也决定你该优化到什么复杂度。主动问规模是加分项。
3. 说暴力解和它的复杂度。不要跳过这一步。暴力解暴露了重复劳动在哪里，优化就是消灭这些重复劳动。展示思路演进，比直接背最优解可信。
4. 优化并写代码。写的时候说出关键行在干什么。
5. 写完走一个例子，报时间复杂度和空间复杂度，并说清复杂度来自哪里。

节奏：单题上限 25 分钟。超时先看提示再看题解，看过题解隔天必须重写一遍。错题按错因归类：边界没想全、思路方向错、代码写崩，三类错因对应三种练法。同一类错因出现三次，就是你的真弱点。

下面十个题解全部按这五步走。第一遍读的时候，把每个"优化推导"当成第 3 步到第 4 步的现场演示来模仿。

## 1. 数组与双指针

**识别特征**。输入是有序数组（或者可以排序），要在一堆数对里找满足条件的组合；或者要求原地修改数组、不开新空间。

**通用套路**。对撞指针：一个下标从左往右，一个从右往左，根据中间结果决定移动哪一端。同向快慢指针：一个读一个写，常用于原地删除、去重。

**为什么这样想**。找所有数对，暴力是两层循环，O(n²)。有序性给了你"淘汰"的资格：看一眼两端的和，就能断定某一端再也不可能参与答案，把它整段排除。每步淘汰一个候选，n 步以内结束，这就是 O(n) 的来源。

### 题解：两数之和 II（LeetCode 167）

**题面**。给定升序数组 `numbers` 和目标值 `target`，找出和为 `target` 的两个数，返回下标（从 1 开始）。每个输入恰好有一组解。

**暴力解**。枚举所有数对，找到就返回。时间 O(n²)。

**优化推导**。设左指针 `left`、右指针 `right`。若 `numbers[left] + numbers[right] > target`：数组升序，`numbers[right]` 与区间里任何更靠左的数的和都不会更小，全都大于 `target`。所以 `numbers[right]` 不可能再配对成功，淘汰它，`right--`。对称地，和小于 `target` 时淘汰 `numbers[left]`，`left++`。两个指针相向而行，相遇仍未找到才返回不存在。

```java
public class TwoSumII {
    // LeetCode 167: 升序数组里找和为 target 的两个数，返回下标（从 1 开始）
    public static int[] twoSum(int[] numbers, int target) {
        int left = 0, right = numbers.length - 1;
        while (left < right) {
            int sum = numbers[left] + numbers[right];
            if (sum == target) return new int[]{left + 1, right + 1};
            if (sum < target) left++;
            else right--;
        }
        return new int[]{-1, -1};
    }

    public static int[] brute(int[] numbers, int target) {
        for (int i = 0; i < numbers.length; i++)
            for (int j = i + 1; j < numbers.length; j++)
                if (numbers[i] + numbers[j] == target) return new int[]{i + 1, j + 1};
        return new int[]{-1, -1};
    }

    public static void main(String[] args) {
        int[] numbers = {2, 7, 11, 15};
        System.out.println(java.util.Arrays.toString(twoSum(numbers, 9)));
        System.out.println(java.util.Arrays.toString(brute(numbers, 9)));
        System.out.println(java.util.Arrays.toString(twoSum(new int[]{2, 3, 4}, 6)));
    }
}
```

```text
输出：
[1, 2]
[1, 2]
[1, 3]
```

第一行是对撞指针的结果，第二行是暴力解的结果，两者一致；第三行验证和为 6 时选中 2 和 4。程序在找到答案的第一刻返回，不会继续扫。

**复杂度**。时间 O(n)：每个元素最多被淘汰一次，淘汰总数不超过 n。额外空间 O(1)：只有两个下标变量。对比暴力 O(n²)：有序性把"逐对枚举"换成了"逐个淘汰"。

**常见错误**。三数之和（LeetCode 15）是这题的扩展：排序后固定一个数，剩下的区间用对撞指针。忘记跳过重复元素，会输出重复的三元组。

## 2. 滑动窗口

**识别特征**。题目要"连续"的子串或子数组，且要求随长度变化的性质：无重复字符、和至少为某个值、至多 K 种元素。

**通用套路**。右指针不断向右扩一格；窗口性质被破坏时，左指针向右缩，直到性质恢复。窗口内维护一个能增量更新的状态，比如计数表。

**为什么这样想**。枚举所有子串是 O(n²) 个。关键观察：固定右端 r，让窗口合法的最小左端 L(r) 随 r 只增不减。反证：若某个左端在右端为 r 时不合法（窗口里有重复），右端变成 r+1 后重复只会保留不会消失，它仍然不合法。所以合法的左端随右端单调右移，两个指针都只走 n 步，总代价 O(n)，而不是每扩一格都从头缩一遍的 O(n²)。

### 题解：无重复字符的最长子串（LeetCode 3）

**题面**。给定字符串 `s`，返回不含重复字符的最长子串的长度。

**暴力解**。枚举每个起点，向右延伸到出现重复为止，用一个布尔数组记"窗口里有哪些字符"。时间 O(n²)。

**优化推导**。给每个字符记"上次出现的下标"。右端扩到字符 `c` 时，若 `c` 的上次出现位置落在当前窗口内，那么新的合法左端必须越过那次出现：任何不越过它的左端，窗口里都还留着这个重复。于是 `left` 直接跳到 `last[c] + 1`。跳跃和一格一格收缩等价，但一次到位。

```java
public class LengthOfLongestSubstring {
    // LeetCode 3: 不含重复字符的最长子串长度
    public static int lengthOfLongestSubstring(String s) {
        int[] last = new int[128];
        java.util.Arrays.fill(last, -1);
        int best = 0, left = 0;
        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);
            if (last[c] >= left) left = last[c] + 1;
            last[c] = right;
            best = Math.max(best, right - left + 1);
        }
        return best;
    }

    public static int brute(String s) {
        int best = 0;
        for (int i = 0; i < s.length(); i++) {
            boolean[] seen = new boolean[128];
            for (int j = i; j < s.length(); j++) {
                if (seen[s.charAt(j)]) break;
                seen[s.charAt(j)] = true;
                best = Math.max(best, j - i + 1);
            }
        }
        return best;
    }

    public static void main(String[] args) {
        String[] cases = {"abcabcbb", "bbbbb", "pwwkew"};
        for (String s : cases) {
            System.out.println(s + ": " + lengthOfLongestSubstring(s) + " / " + brute(s));
        }
    }
}
```

```text
输出：
abcabcbb: 3 / 3
bbbbb: 1 / 1
pwwkew: 3 / 3
```

`abcabcbb` 答案 3（`abc`）；`bbbbb` 答案 1（单个 `b`）；`pwwkew` 答案 3（`kew`，注意不是 `pw` 开头的 2）。斜杠后面是暴力解，两者一致。

**复杂度**。时间 O(n)：`right` 走 n 步，`left` 只前进不后退，合计最多 2n 次移动。空间 O(1)：计数表长度固定 128（覆盖全部 ASCII 字符），与输入长度无关。

**常见错误**。收缩条件写反（重复时不缩、不重复时缩），窗口会越缩越短；或者用 `last[c] + 1` 时忘了和 `left` 取大，左指针跳回去，产生假的无重复窗口。判断 `last[c] >= left` 就是为了只对"上次出现还在窗口内"的字符跳跃。

## 3. 哈希表

**识别特征**。题目反复问"某个值存不存在、出现几次、下标是多少"。把线性扫描的查询换成常数时间查询，是这类题唯一的杠杆。

**通用套路**。一遍遍历，把"已经看过的信息"存进 `HashMap`：值到下标、值到次数、前缀和到次数。先查再存还是先存再查，决定一个元素能不能和自己配对。

**为什么这样想**。两数之和的暴力是"每个数配每个数"。换一个角度：对每个 `x`，你要的其实是"`target - x` 在前面出现过吗，在哪"。这是一个查找问题。查找结构选哈希表，平均一次查找 O(1)。先查后存，当前元素还没进表，天然不会和自己配对。

### 题解：两数之和（LeetCode 1）

**题面**。给定整数数组 `nums` 和目标值 `target`，返回和为 `target` 的两个元素的下标。每个输入恰好有一组解，同一个元素不能用两次。

**暴力解**。两层循环枚举所有数对，O(n²)。

**优化推导**。遍历到下标 `i` 时先查 `target - nums[i]` 在不在表里，在就返回两个下标；不在才把 `nums[i] -> i` 存入。`[3, 3]` 加 `target = 6` 是关键用例：处理第二个 3 之前，第一个 3 已经在表里，所以能配对；而处理第一个 3 时表是空的，它不会和自己配对。

```java
public class TwoSum {
    // LeetCode 1: 和为 target 的两个元素下标
    public static int[] twoSum(int[] nums, int target) {
        java.util.HashMap<Integer, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < nums.length; i++) {
            Integer j = seen.get(target - nums[i]);
            if (j != null) return new int[]{j, i};
            seen.put(nums[i], i);
        }
        return new int[]{-1, -1};
    }

    public static int[] brute(int[] nums, int target) {
        for (int i = 0; i < nums.length; i++)
            for (int j = i + 1; j < nums.length; j++)
                if (nums[i] + nums[j] == target) return new int[]{i, j};
        return new int[]{-1, -1};
    }

    public static void main(String[] args) {
        System.out.println(java.util.Arrays.toString(twoSum(new int[]{2, 7, 11, 15}, 9)));
        System.out.println(java.util.Arrays.toString(twoSum(new int[]{3, 2, 4}, 6)));
        System.out.println(java.util.Arrays.toString(twoSum(new int[]{3, 3}, 6)));
        System.out.println(java.util.Arrays.toString(brute(new int[]{3, 3}, 6)));
    }
}
```

```text
输出：
[0, 1]
[1, 2]
[0, 1]
[0, 1]
```

第三行 `[3, 3]` 返回 `[0, 1]`：先查后存让两个相等的值也能配对，且不会把同一个下标用两次。第四行暴力解给出同样结果。

**复杂度**。时间均摊 O(1) 每次 `get`/`put`，总共 O(n)。"均摊"从哪来：`HashMap` 扩容时把旧数组全部搬到新数组，单次最坏 O(n)，但容量翻倍式增长下，n 次 `put` 的总搬家成本是 O(n)，摊到每次操作是常数。最坏单次 O(log n)：Java 8 及以上的 `HashMap` 在单个桶的链表过长时把它转成红黑树。空间 O(n)：表里最多存 n 个键值对。

**常见错误**。先存后查，当前元素会和自己配对（`[3, 2, 4]` 加 `target = 6` 会错误地返回两个 3，即同一个下标 0 用两次）；或者把 `get` 返回的 `null` 当成下标 0 用，需要用 `Integer` 接住并判空。

## 4. 栈与队列

**识别特征**。"最近发生的那件事必须最先处理"：括号匹配、嵌套结构、浏览器的后退。"最值或极值随进出变化"：单调栈、滑动窗口最大值。前一类用栈（后进先出），后一类看具体单调性。

**通用套路**。括号匹配：栈里存"待匹配的右括号"，遇到右括号就和栈顶对。单调栈：栈里存下标，保持对应的值单调，某个下标被弹出时，它的答案恰好在此刻确定。

**为什么这样想**。有效性要求括号要么嵌套、要么并列，不允许交叉（`([)]` 无效）。于是第一个右括号必须匹配最近的那个未匹配左括号：夹在它俩之间的括号都必须在两者闭合之前完全闭合。"最近"这个词就是后进先出，栈是天生的数据结构。暴力解法是反复删除相邻的成对括号直到删不动，每轮 O(n)、最多 n/2 轮，O(n²)；栈一遍扫完。

### 题解：有效的括号（LeetCode 20）

**题面**。给定只含 `()[]{}` 的字符串，判断是否有效：左括号必须用同类型的右括号闭合，且顺序正确。

**优化推导**。遇到左括号，把对应的右括号压栈，后面栈顶直接和到来的字符比较，省去查配对表。遇到右括号：栈空说明没有可匹配的左括号，无效；栈顶和它不同，无效。扫完后栈必须为空，否则还有左括号没闭合。

```java
public class ValidParentheses {
    // LeetCode 20: 判断括号串是否有效
    public static boolean isValid(String s) {
        java.util.ArrayDeque<Character> stack = new java.util.ArrayDeque<>();
        for (char c : s.toCharArray()) {
            if (c == '(') stack.push(')');
            else if (c == '[') stack.push(']');
            else if (c == '{') stack.push('}');
            else if (stack.isEmpty() || stack.pop() != c) return false;
        }
        return stack.isEmpty();
    }

    public static void main(String[] args) {
        String[] cases = {"()[]{}", "(]", "([)]", "{[]}", "("};
        for (String s : cases) System.out.println(s + " -> " + isValid(s));
    }
}
```

```text
输出：
()[]{} -> true
(] -> false
([)] -> false
{[]} -> true
( -> false
```

`([)]` 是典型的交叉，栈顶期待 `)` 却来了 `]`，判假。`(` 是"左括号有剩余"，靠最后的 `stack.isEmpty()` 兜住。

**复杂度**。时间 O(n)：每个字符入栈、出栈最多各一次。空间 O(n)：最坏全部是左括号，全压栈。

**常见错误**。只数左右括号的个数不看顺序，`([)]` 也能通过计数，但它是无效串。或者栈空时直接 `pop()`，抛出 `java.util.NoSuchElementException`，所以 `isEmpty()` 判断必须先做。

**延伸**。每日温度（LeetCode 739）是单调栈的代表题。栈存下标，温度从栈底到栈顶递减；新温度比栈顶高时，栈顶元素"等到了第一个更暖的日子"，弹出并写答案。每个下标只入栈、出栈各一次，O(n)。

```java
// 片段：每日温度（LeetCode 739）的单调栈核心，不能独立运行。
java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
for (int i = 0; i < temperatures.length; i++) {
    while (!stack.isEmpty() && temperatures[i] > temperatures[stack.peek()]) {
        int j = stack.pop();
        answer[j] = i - j;   // 下标 j 等到了第一个更暖和的日子
    }
    stack.push(i);
}
```

## 5. 链表

**识别特征**。单向链表只能从头往后走，不能随机访问，走到某个节点后回不来。题目涉及改指向、找中点、倒数第 k 个、判环，都在这个限制下做文章。

**通用套路**。哑结点（dummy node）：在头节点前挂一个假节点，"删除头节点"和"删除中间节点"变成同一段代码，最后返回 `dummy.next`。快慢指针：找中点（快指针每次两步）、判环（快慢相遇）、倒数第 k（快指针先走 k 步）。三指针：原地反转。

**为什么这样想**。反转的本质是把每个节点"摘下来，头插到已反转部分的前面"。走链表时一旦把 `curr.next` 改掉，后面的节点就找不到了，所以摘之前必须先把后继存进临时变量。整个过程只需要三个引用：`prev`（已反转部分的头）、`curr`（待处理节点）、`next`（防断链的备份）。

### 题解：反转链表（LeetCode 206）

**题面**。给定单链表头节点 `head`，反转链表并返回新头。

**暴力解**。把值抄进数组，倒着重建一条新链。O(n) 时间 O(n) 空间，但没有复用原节点，面试里通常要求指针操作、O(1) 空间的版本。

**优化推导**。循环不变量：`prev` 开头的链是已反转的前缀，`curr` 开头的链是未处理的后缀。每一步：备份 `curr.next` 到 `next`，让 `curr` 指回 `prev`（完成头插），再把 `prev`、`curr` 各右移一位。不变量始终保持。`curr` 走到 `null` 时后缀为空，`prev` 就是新头。

```java
public class ReverseList {
    // LeetCode 206: 原地反转单链表
    static class ListNode {
        int val;
        ListNode next;
        ListNode(int val) { this.val = val; }
    }

    public static ListNode reverseList(ListNode head) {
        ListNode prev = null;
        ListNode curr = head;
        while (curr != null) {
            ListNode next = curr.next;   // 先备份，否则断链后找不到后继
            curr.next = prev;
            prev = curr;
            curr = next;
        }
        return prev;
    }

    static ListNode build(int... vals) {
        ListNode dummy = new ListNode(0);   // 哑结点：建链和返转都不用单独处理头
        ListNode tail = dummy;
        for (int v : vals) {
            tail.next = new ListNode(v);
            tail = tail.next;
        }
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
        System.out.println(show(reverseList(build(1, 2, 3, 4, 5))));
        System.out.println(show(reverseList(build(7))));
        System.out.println(show(reverseList(null)));
    }
}
```

```text
输出：
[5, 4, 3, 2, 1]
[7]
[]
```

后两行是边界：单节点反转还是自己，空链表反转还是空。这两个用例面试时要主动走一遍。

**复杂度**。时间 O(n)：每个节点处理一次。空间 O(1)：只有 `prev`、`curr`、`next` 三个引用，就地改指针。

**常见错误**。不备份 `next` 就改 `curr.next`，链表从改动点断成两截，后半段永久丢失。初学者这么写是因为"改指向"这个动作看起来只需要一步，实际上链表的单向性让"先存后断"成为硬性顺序。

## 6. 二叉树与递归三要素

**识别特征**。问题能拆成"左右子树的同型子问题"：统计（深度、节点数）、判定（是否对称、是否合法搜索树）、构造。

**通用套路**。递归三要素，写任何树的递归之前先在纸上回答：第一，终止条件是什么，通常是 `root == null` 返回什么；第二，本层做什么，拿到子问题的结果之后做哪几步；第三，向父层返回什么，返回值的含义要先定死，中途不改。

**为什么这样想**。以最大深度为例。定义：从根到最远叶子的节点数。暴力做法是对每个节点单独算"从它往下能走多深"，每次都要走遍它的整棵子树，同一批节点被反复向下走，链状树最坏 O(n²)。优化做法是让函数直接返回"以我为根的子树的高度"，父亲只读两个孩子的高度，自己不再往下走。返回值设计对了，重复计算就消失了：每个节点只算一次，O(n)。这就是第三要素"返回值含义"的分量。

### 题解：二叉树的最大深度（LeetCode 104）

**题面**。给定二叉树根节点，返回最大深度。空树深度为 0。

**优化推导**。终止条件：空树深度 0。本层做什么：什么都不用做，只需要收集两个孩子的结果。返回值含义：以本节点为根的子树的最大深度。于是整棵树的答案就是 `1 + max(左子树深度, 右子树深度)`。

```java
public class MaxDepth {
    // LeetCode 104: 二叉树最大深度
    static class TreeNode {
        int val;
        TreeNode left;
        TreeNode right;
        TreeNode(int val) { this.val = val; }
    }

    public static int maxDepth(TreeNode root) {
        if (root == null) return 0;   // 三要素一：终止条件
        return 1 + Math.max(maxDepth(root.left), maxDepth(root.right));   // 二：本层合并；三：返回子树深度
    }

    public static void main(String[] args) {
        TreeNode root = new TreeNode(3);
        root.left = new TreeNode(9);
        root.right = new TreeNode(20);
        root.right.left = new TreeNode(15);
        root.right.right = new TreeNode(7);
        System.out.println(maxDepth(root));
        System.out.println(maxDepth(null));
        System.out.println(maxDepth(new TreeNode(1)));
    }
}
```

```text
输出：
3
0
1
```

样例树的最深路径是 `3 -> 20 -> 15`，深度 3。

**复杂度**。时间 O(n)：每个节点恰好访问一次。空间 O(h)：h 是树高，来自递归调用栈；链状树最坏 O(n)，平衡树 O(log n)。对比同复杂度的层序遍历（用队列一层层数）：时间也是 O(n)，但空间是 O(w)，w 是最宽一层的节点数，退化时同样到 O(n)。两者选一个写熟即可，递归版代码量小得多。

**常见错误**。终止条件写成 `root.left == null && root.right == null` 返回 1，空树就没有终止点了，直接空指针异常。初学者这么写是因为把"叶子"当成了最小情形，而树递归的最小情形永远是"空"。

## 7. BFS 与 DFS

**识别特征**。网格或图上的连通性问题：数连通块、求最短步数、判断能否到达。无权图求最短距离必须用 BFS（广度优先搜索，逐层扩展）；只数连通块、标记可达区域，DFS（深度优先搜索，一条路走到底再回头）和 BFS 都行。

**通用套路**。两层结构：外层扫描所有格子找起点，内层从起点出发把整个连通块全部标记。标记方式两种：直接改原数组（本题沉岛），或另开 `visited` 布尔数组。BFS 用队列，DFS 用递归。

**为什么这样想**。直观问法"每个陆地格属于哪个岛"，回答一次就要做一次全图搜索，量级到 O((mn)²)。两条关键观察把它压到 O(mn)。观察一：从一个未标记的陆地格出发，四连通能走到的所有陆地格，恰好是一个完整的岛。观察二：数完一个岛就把它的格子全部改成 `'0'`。之后扫描再遇到 `'1'`，这个格子不可能属于已数过的岛，因为那些格子已经变成 `'0'`，搜索走不进去，所以必然是新岛。

### 题解：岛屿数量（LeetCode 200）

**题面**。给定 `'1'`（陆地）和 `'0'`（水）组成的二维网格，数岛屿的数量。岛由上下左右相邻的陆地组成，斜角不算。

**优化推导**。扫描每个格子；遇到 `'1'`，计数加一，同时从它出发把整个岛"沉掉"（递归把每个相连的陆地格改成 `'0'`）。沉岛函数的终止条件：越界，或者当前格不是陆地。

```java
public class NumIslands {
    // LeetCode 200: 数网格里的岛屿数量
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

    static void sink(char[][] grid, int r, int c) {
        if (r < 0 || r >= grid.length || c < 0 || c >= grid[0].length || grid[r][c] != '1') return;
        grid[r][c] = '0';   // 标记：这一步不能少，见下面的反例
        sink(grid, r + 1, c);
        sink(grid, r - 1, c);
        sink(grid, r, c + 1);
        sink(grid, r, c - 1);
    }

    public static void main(String[] args) {
        char[][] grid = {
            {'1', '1', '0', '0', '0'},
            {'1', '1', '0', '0', '0'},
            {'0', '0', '1', '0', '0'},
            {'0', '0', '0', '1', '1'}
        };
        System.out.println(numIslands(grid));
        System.out.println(numIslands(new char[][]{{'1'}, {'0'}, {'1'}}));
    }
}
```

```text
输出：
3
2
```

样例网格里左上角 2x2 是一个岛，中间的 `1` 是一个，右下角横着的 `1,1` 是一个，共 3 个。

**复杂度**。时间 O(mn)：每个格子最多被访问常数次（自身一次，加来自邻居的方向次数有上界）。空间最坏 O(mn)：整个网格都是陆地时，递归深度达到 mn。

**反例：不标记会怎样**。初学者常把 `grid[r][c] = '0';` 那一行删掉，理由是"只读不改看起来更安全"。实际发生的是：相邻的两个 `'1'` 互相把对方当未处理的邻居，无限互调，直到调用栈耗尽。下面是删掉标记行之后的完整程序，真实运行结果如下。

```java
public class NoMark {
    // 错误版本：沉岛时不做标记
    static void sink(char[][] grid, int r, int c) {
        if (r < 0 || r >= grid.length || c < 0 || c >= grid[0].length || grid[r][c] != '1') return;
        sink(grid, r + 1, c);
        sink(grid, r - 1, c);
        sink(grid, r, c + 1);
        sink(grid, r, c - 1);
    }

    public static void main(String[] args) {
        char[][] grid = {{'1', '1'}};
        sink(grid, 0, 0);
        System.out.println("done");
    }
}
```

```text
运行异常：
Exception in thread "main" java.lang.StackOverflowError
```

两个相邻的 `'1'` 互相递归，没有任何前进，栈帧耗尽。怎么改：进入函数、通过检查后第一步就标记。改法的局限：沉岛破坏了输入，题目不允许改原数组时，改用等大小的 `visited` 数组，逻辑完全一样。

**延伸**。腐烂的橘子（LeetCode 994）是多源 BFS：所有腐烂橘子先全部入队，再逐层扩散，层数就是扩散时间。多源和单源的唯一区别是队列的初始内容，套路不变。

## 8. 堆

**识别特征**。"第 K 大/小"、"前 K 个"、"数据流里随时取最值"。数据不需要完全有序，只需要随时能拿到最值，就是堆的信号。

**通用套路**。求第 K 大：维护一个大小恒为 K 的小顶堆。求第 K 小：对称地用大顶堆。流式处理：每个元素进堆，超过 K 就弹出堆顶。`PriorityQueue` 默认是小顶堆；要大顶堆，构造时传 `Comparator.reverseOrder()`。

**为什么这样想**。排序 O(n log n) 把所有元素都排了，但答案只问第 K 名。真正需要维护的是"目前为止最大的 K 个数"，这 K 个里最小的就是第 K 大，它是候选门槛。归纳论证：堆里装着前 K 大。新元素 `x` 来了，若 `x` 不大于堆顶（当前 K 个最大里的最小者），`x` 进不了前 K，丢弃；若 `x` 更大，堆顶从此进不了前 K，弹出它，`x` 入堆，性质保持。任意时刻堆顶就是第 K 大。

### 题解：数组中第 K 个最大元素（LeetCode 215）

**题面**。给定整数数组 `nums` 和整数 `k`，返回数组中第 K 个最大的元素。注意是排序后的第 K 个，不是第 K 个不同的值。

**暴力解**。排序后取倒数第 K 个，O(n log n) 时间，O(n) 或 O(log n) 空间看排序实现。

**优化推导**。如上归纳：小顶堆保 K 个最大，堆顶即答案。对比全量排序：K 远小于 n 时，大量比门槛小的元素只花 O(1)（入堆后立刻弹出，堆不增长），而不是 O(log n)。

```java
public class KthLargest {
    // LeetCode 215: 数组第 K 大元素
    public static int findKthLargest(int[] nums, int k) {
        java.util.PriorityQueue<Integer> heap = new java.util.PriorityQueue<>();
        for (int x : nums) {
            heap.offer(x);
            if (heap.size() > k) heap.poll();   // 弹掉的是当前 K 个最大里最小的
        }
        return heap.peek();
    }

    public static void main(String[] args) {
        System.out.println(findKthLargest(new int[]{3, 2, 1, 5, 6, 4}, 2));
        System.out.println(findKthLargest(new int[]{3, 2, 3, 1, 2, 4, 5, 5, 6}, 4));
    }
}
```

```text
输出：
5
4
```

第一个用例排序后是 `1,2,3,4,5,6`，第 2 大是 5。第二个用例有重复值，排序后第 4 大是 4。

**复杂度**。时间 O(n log k)：每个元素一次 `offer` 加至多一次 `poll`，堆大小不超过 k，堆操作 O(log k)。空间 O(k)。"堆操作为什么是 log"：堆是完全二叉树，树高约 log₂k，上浮和下沉最多走一层树高；取堆顶 `peek` 是 O(1)，因为最值永远在根上。

**常见错误**。求第 K 大误用大顶堆：全量装进大顶堆要 O(n) 空间，还得弹 K-1 次才见答案；而小顶堆随时可答。另一个是以为堆里的元素有序，堆只保证父子间的大小关系，兄弟之间无序，逐个弹出才是有序输出。

## 9. 动态规划入门

**识别特征**。问最大值、最小值或方案数；决策有先后顺序；当前位置的最优解只依赖前面有限个位置（无后效性）。一维线性结构上的这类问题，是动态规划的入门。

**通用套路**。五步：第一，一句话定义状态 `dp[i]` 的含义；第二，从"最后一步"倒推转移方程；第三，定初值；第四，确定计算顺序；第五，看转移只依赖哪几项，压缩空间。

**为什么这样想**。以打家劫舍（LeetCode 198）为例：一排房子，不能偷相邻两家，求最大收益。暴力枚举每户"偷或不偷"，写成递归：对第 `i` 户，要么不偷（收益等于前 `i-1` 户的最优），要么偷（第 `i-1` 户必须不偷，收益是前 `i-2` 户的最优加本户）。重复劳动在这里：`f(i-2)` 被 `f(i)` 和 `f(i-1)` 各算一遍，展开后每个子问题被算指数次。下面的程序给暴力递归装了个计数器，看调用次数怎么涨。

### 题解：打家劫舍（LeetCode 198）

**题面**。给定非负整数数组 `nums`，不能偷相邻两户，求能偷到的最高金额。

**暴力解**。递归 `robBrute(nums, i) = max(robBrute(nums, i-1), robBrute(nums, i-2) + nums[i])`。正确，但慢。

**优化推导**。调用次数满足 `T(n) = T(n-1) + T(n-2) + 1`，和斐波那契数列同型，指数增长。实测输出里 n 每加 10，调用次数约乘 123（1.618 的 10 次方约 123）。两种修法：记忆化，把算过的 `f(i)` 存进数组，第二次直接取；自底向上，从最小子问题算到最大，即动态规划。转移只依赖前两项，数组可以压成两个变量。

```java
public class HouseRobber {
    // LeetCode 198: 不相邻取数求最大和
    static long calls;

    static int robBrute(int[] nums, int i) {
        calls++;
        if (i < 0) return 0;
        return Math.max(robBrute(nums, i - 1), robBrute(nums, i - 2) + nums[i]);
    }

    public static int rob(int[] nums) {
        int prev2 = 0, prev1 = 0;   // prev2 = dp[i-2]，prev1 = dp[i-1]
        for (int x : nums) {
            int cur = Math.max(prev1, prev2 + x);
            prev2 = prev1;
            prev1 = cur;
        }
        return prev1;
    }

    public static void main(String[] args) {
        int[] nums = {2, 7, 9, 3, 1};
        calls = 0;
        System.out.println("robBrute({2,7,9,3,1}) = " + robBrute(nums, 4) + ", calls=" + calls);
        for (int n : new int[]{20, 30, 40}) {
            int[] big = new int[n];
            java.util.Arrays.fill(big, 1);
            calls = 0;
            robBrute(big, n - 1);
            System.out.println("n=" + n + " calls=" + calls);
        }
        System.out.println("rob({2,7,9,3,1}) = " + rob(new int[]{2, 7, 9, 3, 1}));
        System.out.println("rob({1,2,3,1}) = " + rob(new int[]{1, 2, 3, 1}));
    }
}
```

```text
输出：
robBrute({2,7,9,3,1}) = 12, calls=25
n=20 calls=35421
n=30 calls=4356617
n=40 calls=535828591
rob({2,7,9,3,1}) = 12
rob({1,2,3,1}) = 4
```

n=40 时暴力递归被调用了 5.36 亿次，而动态规划版本对同样的输入只做 40 次循环。两个算法的答案完全一致（第一行和第五行都是 12），差的是重复劳动。

**复杂度**。动态规划版本时间 O(n)：每户算一次。空间 O(1)：转移只依赖前两项，两个变量滚动即可；不压缩的数组版本是 O(n)。

**常见错误**。状态定义含糊导致转移写错：`dp[i]` 到底是"前 i 户"还是"必须偷第 i 户"，含义不同转移完全不同。初学者的对策是把定义写成一句中文放在代码注释里，写转移前先读一遍。爬楼梯（LeetCode 70）是同一递推形状的方案数版本：`f(i) = f(i-1) + f(i-2)`，最后一步要么跨 1 阶要么跨 2 阶。

## 10. 二分查找

**识别特征**。输入有序（或者"对答案值域，判定函数单调"），要找某个值的位置或某个边界。

**通用套路**。先写循环不变量，再写边界。不变量是一句断言，每次循环开始时都成立，例如"若 target 存在，它的下标一定在 `[lo, hi]` 内"。循环条件和更新式必须和不变量配套，不确定 `<=` 还是 `<` 时，回到不变量推，不要背。

**为什么这样想**。有序数组里查一个数，暴力线性扫 O(n)。有序性的价值：看一个中间元素，就能排除整整一半。排除一半的动作重复，n 个候选最多 log₂n 轮就见底。

### 题解：二分查找（LeetCode 704）

**题面**。给定升序数组 `nums` 和目标值 `target`，返回下标，不存在返回 -1。

**优化推导**。不变量：进入每轮循环时，若 target 在数组里，其下标必在 `[lo, hi]` 内。初始 `[0, n-1]` 成立。比较 `nums[mid]`：相等即返回；`nums[mid] < target` 说明 `[lo, mid]` 整段都小于 target（升序），淘汰左半，`lo = mid + 1` 之后不变量仍成立；对称地淘汰右半。循环退出时区间为空，target 不存在，返回 -1。区间每轮至少缩一半，所以最多 log₂n 加一轮。

```java
public class BinarySearch {
    // LeetCode 704: 升序数组中找 target 的下标
    public static int search(int[] nums, int target) {
        int lo = 0, hi = nums.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;   // 不写 (lo + hi) / 2，防下标相加溢出
            if (nums[mid] == target) return mid;
            if (nums[mid] < target) lo = mid + 1;
            else hi = mid - 1;
        }
        return -1;
    }

    public static int linear(int[] nums, int target) {
        for (int i = 0; i < nums.length; i++)
            if (nums[i] == target) return i;
        return -1;
    }

    public static void main(String[] args) {
        int[] nums = {-5, -1, 0, 3, 9, 12};
        System.out.println(search(nums, 9) + " / " + linear(nums, 9));
        System.out.println(search(nums, -5) + " / " + linear(nums, -5));
        System.out.println(search(nums, 2) + " / " + linear(nums, 2));
    }
}
```

```text
输出：
4 / 4
0 / 0
-1 / -1
```

三行分别是命中中部、命中头、未命中。斜杠后是线性扫描，结果一致。

`mid = lo + (hi - lo) / 2` 不是炫技。写成 `(lo + hi) / 2`，当 `lo` 和 `hi` 都很大（元素个数接近 int 上限 21 亿）时 `lo + hi` 溢出成负数，`mid` 变负，`nums[mid]` 直接数组越界。这是 2006 年 Joshua Bloch 公开指出、藏在各大标准库里多年的真实 bug。

**复杂度**。时间 O(log n)：每轮区间至少减半，最多 log₂n 加一轮。空间 O(1)：只有三个下标变量。

**延伸**。二分答案是同一套不变量换个靶子：题目不让你在数组里找，而是在"答案的可能取值范围"里找最小可行值，比如爱吃香蕉的珂珂（LeetCode 875）：吃速 k 越大耗时越短，"k 是否可行"单调，所以对 k 二分。判定函数单调，就能二分。

## 复杂度怎么报：给结论也给来源

面试报复杂度的句式：「时间 O(n log n)，来自排序；额外空间 O(n)，来自哈希表」。只报阶不报来源，追一句"为什么"就接不住。常用结构的能力边界：

| 结构 | 查找 | 插入/删除 | 复杂度来源 |
|------|------|-----------|------------|
| 数组 | O(n) | O(n) | 按值只能逐个比；移动后续元素 |
| 有序数组 | O(log n) | O(n) | 二分每轮排除一半；保序仍要搬移 |
| 链表 | O(n) | O(1)，需持有前驱引用 | 不能随机访问；改指针即可 |
| 哈希表 | 均摊 O(1) | 均摊 O(1) | 扩容成本摊还；Java 8+ 桶树化后最坏 O(log n) |
| 平衡二叉搜索树 | O(log n) | O(log n) | 树高 log n |
| 堆 | 取顶 O(1) | O(log k) | 完全二叉树高 log k |

## LinkForge 里的算法：三个真实用例

以上题型不是纯做题技巧，LinkForge 的热路径上就跑着三个。这一节把算法和工程接起来。

### 布隆过滤器：宁可误报，不可漏报

短链跳转是最热的路径，而攻击者最喜欢拿随机短码去轰它。每个不存在的码如果都要走一遍 Redis 未命中加 MySQL 查询，数据库就被打穿了。这个需求需要一个用极小内存就能回答"这个码肯定不存在吗"的结构，答案是布隆过滤器：一个位数组加 k 个哈希函数。插入时把 k 个哈希位置置 1；查询时 k 个位置全为 1 答可能有，任一为 0 答肯定没有。

用 JDK 自己写一个 64 位的迷你版看它的行为：

```java
public class MiniBloom {
    static final int BITS = 64;
    static long bits = 0;

    static int slot1(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return Math.floorMod(h, BITS);
    }

    static int slot2(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = 131 * h + s.charAt(i);
        return Math.floorMod(h, BITS);
    }

    static void put(String s) {
        bits |= 1L << slot1(s);
        bits |= 1L << slot2(s);
    }

    static boolean mightContain(String s) {
        return (bits >>> slot1(s) & 1) == 1 && (bits >>> slot2(s) & 1) == 1;
    }

    public static void main(String[] args) {
        put("abc");
        for (int i = 0; i < 8; i++) put("y" + i);
        System.out.println("abc -> " + mightContain("abc"));
        System.out.println("abd -> " + mightContain("abd"));
        int misses = 0;
        for (int i = 0; i < 8; i++) if (!mightContain("y" + i)) misses++;
        System.out.println("misses among inserted: " + misses);
        int fp = 0;
        String first = null;
        for (int i = 0; i < 100000; i++) {
            String s = "x" + i;
            if (mightContain(s)) {
                fp++;
                if (first == null) first = s;
            }
        }
        System.out.println("false positives in 100000 probes: " + fp);
        System.out.println("first: " + first);
    }
}
```

```text
输出：
abc -> true
abd -> false
misses among inserted: 0
false positives in 100000 probes: 6170
first: x98
```

读这五行。插入过的 `abc` 和全部 8 个 `y` 码都答 true，`misses among inserted: 0`，只要实现不消除任何位，插入过的元素永远答 true，漏报在结构上不可能。反过来，从未插入的 `x98` 也答了 true，10 万个未插入探针里 6170 个被误判，约 6%。这个比例能算出来：8 个字符串置了约 16 个位，64 位里点了四分之一，两个探测位都落进被置位的概率约 (16/64)² 约等于 6%。位数组越大、哈希函数个数越合适，这个比例越低，但它永远大于零。

为什么这种"会误报"的结构敢挡在跳转路径上？看两个方向错误各自的代价。漏报，即存在的码答"没有"，正常用户的链接直接 404，正确性被破坏，不可接受。误报，即不存在的码答"可能有"，只是多走一次缓存加数据库，最终结果仍然正确，可以接受。所以工程上对布隆过滤器的用法永远是：答"没有"时直接下结论，答"可能有"时继续走完整流程。它只朝安全的方向出错。

LinkForge 的实现是 `src/main/java/com/linkforge/link/support/CodeBloomFilter.java`。`LinkForgeProperties.java` 第 110 到 112 行给出配置：预期插入 10 万条，目标误报率 `fpp = 0.0001`。按布隆过滤器的标准最优参数公式，位数 m = -n·ln(p)/(ln 2)²，约 192 万个位，折合约 234 KB；哈希函数个数 k = (m/n)·ln 2，约 13 个。10 万个短码的记忆成本不到 1 MB，这就是不建 `HashSet` 存码的理由。

流程对照源码：应用就绪事件里 `selectAllCodes()` 全量播种（第 59 到 62 行）；创建短链成功后 `codeBloom.put(link.getCode())` 让新码立刻可见（`src/main/java/com/linkforge/link/service/ShortLinkService.java` 创建逻辑末尾）；重定向路径先问过滤器，第 382 行 `if (!codeBloom.mightContain(code))` 为真就抛 404，不碰 MySQL。`mightContain` 的失败策略在第 81 到 87 行：禁用、未就绪、加载失败一律返回 true，放行走缓存加数据库。据代码推断，这是可用性优先于预过滤优化的设计，加载失败绝不能把跳转打挂。两个边界要记住：经典布隆不能删除，删掉的码仍然答 true，代价是多查一次数据库；多实例部署时各实例各播各的副本，共享过滤器是另一个功能。

### 滑动窗口限流：同一个窗口，另一种数据

第 2 节的滑动窗口在字符串上滑动，这里是它在后端的真实岗位：限流。LinkForge 的规则是注册每 IP 每 600 秒 5 次、建链每用户每 60 秒 30 次、跳转每 IP 每 60 秒 300 次（`src/main/java/com/linkforge/config/LinkForgeProperties.java` 第 183 到 190 行）。

先看为什么不用固定窗口（每分钟一个计数器，计数到顶就拒绝）。固定窗口有边界突刺：限每分钟 30 次，调用者在 23:59:59 打满 30 个，00:00:01 再打满 30 个，两秒内实际通过 60 个，瞬时速率达到名义速率的两倍。滑动窗口日志的修法是记录每个被放行请求的时间戳：判定时先删掉窗口之外的旧记录，数一数窗口内还剩几条，少于上限就放行并记录。对照第 2 节：删旧记录是左缩，记录新请求是右扩，同一个算法，数据从计数表换成了时间戳日志。

实现在 `src/main/java/com/linkforge/common/ratelimit/RedisRateLimiter.java` 加 `src/main/resources/redis/sliding_window.lua`。时间戳存进 Redis 的 ZSET（按分数排序的集合，分数就是时间戳），核心判定是这段 Lua 脚本：

```lua
-- 片段：sliding_window.lua 的判定核心，在 Redis 服务端整体执行。
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - win)  -- 左缩：删窗口外的旧记录
local n = redis.call('ZCARD', KEYS[1])                      -- 数窗口内还剩几条
if n < max then
  redis.call('ZADD', KEYS[1], now, now .. '-' .. ARGV[3])   -- 右扩：记录本次放行
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
  return {1, n + 1, 0}
end
```

四个工程细节对应四个"为什么"。第一，查数和记录必须原子：两个实例同时读到 n 等于 max 减一就会都放行，Lua 脚本在 Redis 单线程里整体执行，天然原子，这是 Lua 方案存在的原因。第二，时钟用脚本里的 Redis `TIME` 而不是 JVM 传进来的时间，多实例的机器时钟漂移就干扰不了窗口。第三，只有放行分支才有 `ZADD`，被拒的请求不进窗口，调用者不能靠持续重试延长自己的封锁期。第四，拒绝时脚本用最老记录算出真实的 `Retry-After`（最老记录加窗口减当前时刻），调用者等到点再试一定有名额，而不是收到一个拍脑袋的固定值。判定结果封装在 `src/main/java/com/linkforge/common/ratelimit/RateLimitDecision.java` 的 record 里。

Redis 挂了怎么办：`RedisRateLimiter` 的 `failOpen` 在异常时放行（第 98 到 104 行），同时给 `linkforge.ratelimit.failopen` 计数器加一，让静默降级在监控里可见。据代码推断，同样是可用性优先。同一个类里还保留一个固定窗口的 `tryAcquireFixedWindow`（INCR 加 EXPIRE），只给 API key 的日配额用，因为日配额的语义就是日历日清零，固定窗口反而正确。选算法看语义，不看新旧。

### 随机短码与鸽笼原理：62 的 7 次方为什么够用

每条短链要一个唯一短码。不用自增 ID 当短码的理由：可枚举，爬虫加一就能遍历全站，还暴露了业务量级。LinkForge 的做法是随机生成（`src/main/java/com/linkforge/link/support/ShortCodes.java`）：

```java
// 片段：ShortCodes.randomCode，从 62 字符表随机取字符
private static final char[] ALPHABET =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
private static final SecureRandom RANDOM = new SecureRandom();

public static String randomCode(int length) {
    char[] buf = new char[length];
    for (int i = 0; i < length; i++) {
        buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    }
    return new String(buf);
}
```

每个位置 62 种取法、互相独立，长度 7（`LinkForgeProperties.java` 第 78 行 `codeLength = 7`）时的总空间是 62⁷ = 3,521,614,606,208，约 3.5 万亿个不同的码。鸽笼原理（抽屉原理）在这里的用法是：把 62⁷ 个码看成笼子，已创建的短码看成放进笼子的鸽子。只要鸽子数不超过笼子数，就一定存在不冲突的分配方式，所以长度 7 的空间对短链业务是够的。工程上更要紧的是反面问题：随机生成的新码撞上已存在码的概率有多大。已有 n 个码时，一次生成撞车的概率约为 n/62⁷。一百万条短链时约 2.8×10⁻⁷，平均生成 350 万次才撞一次；就算业务涨到十亿条，也只是 2.8×10⁻⁴。所以撞车是小概率事件，撞了就重试，代价可忽略。

重试逻辑在 `src/main/java/com/linkforge/link/service/ShortLinkService.java` 第 180 行附近：最多尝试 16 次（第 62 行 `CODE_ATTEMPTS = 16`），每次生成新码直接 `insert`，撞上唯一索引就换一个再来。注意兜底的方式：并发窗口里"先查库再用"靠不住，两个实例可能同时生成同一个码、同时查库都通过。真正的防线是 MySQL 的唯一索引 `uk_code`（`src/main/resources/db/schema.sql` 第 44 行），第二个插入抛 `DuplicateKeyException`，被捕获后进入下一轮重试。这是一个通用的工程模式：随机加重试加唯一约束兜底，应用层的存在性检查只负责省重试次数，正确性交给数据库。类里的 `inAlphabet` 是配套的合法性校验，逐字符线性扫描字母表，O(62·L)，字符表固定 62 个，线性扫描没有装箱也没有额外内存，单元测试 `ShortCodesTest` 用它核对每个生成码。

## 自测

每条先自己回答，再看答案。

1. 对撞指针每一步"淘汰一端"的依据是什么？
   答：单调性加淘汰论证。以两数之和 II 为例，和大于 target 时，右端与区间内任何数的和都只会更大，右端不可能再配对成功，整段排除。每步淘汰一个候选，才有 O(n)。
2. 滑动窗口的左指针为什么永不回退？
   答：窗口 `[left, r]` 若不合法（含重复），右端变大后重复仍存在，同样不合法。所以让窗口合法的最小左端随右端单调不减，两个指针各走 n 步，总代价 O(n)。
3. 求第 K 大为什么用小顶堆？堆满之后新元素来了先做什么？
   答：堆里始终是"目前最大的 K 个数"，小顶堆的堆顶正是其中最小者，即第 K 大这个门槛。新元素不大于门槛就丢弃，大于门槛则弹出门槛、自己入堆。`PriorityQueue` 默认就是小顶堆。
4. 打家劫舍的暴力递归慢在哪里？记忆化和自底向上分别消灭了什么？
   答：`f(i-2)` 被 `f(i)` 和 `f(i-1)` 重复计算，调用次数按斐波那契节奏指数增长（实测 n=40 达 5.36 亿次）。两种修法都让每个子问题只算一次；自底向上还能利用"只依赖前两项"把空间压到 O(1)。
5. 二分的循环不变量怎么陈述？`mid` 为什么写成 `lo + (hi - lo) / 2`？
   答：如"若 target 存在，下标必在 `[lo, hi]` 内"，循环条件和更新式都与它配套，退出时区间为空即不存在。`(lo + hi)` 在两个下标都很大时会超出 int 上限溢出成负数，先减后加不溢出。
6. LinkForge 里布隆过滤器答 false 和答 true 分别怎么处理？为什么删除的码过滤不掉？
   答：false 是权威答案，重定向路径直接 404，不碰 MySQL；true（含误报、禁用、未就绪、加载失败）一律放行走缓存加数据库，数据库是正确性仲裁。经典布隆只置位不清位，删除的码的位还在，所以仍答 true，代价是多查一次数据库。

## 下一步

- 每类题型练 3 到 5 道代表题，题目卡片（提示、用例、参考实现）见 [92-interview-coding.md](92-interview-coding.md)。
- 按周推进的完整训练计划见 [00-roadmap.md](00-roadmap.md)，本章十类题型与它的算法列逐周对应。
- 本文用到的集合结构（`HashMap` 扩容与树化、`ArrayDeque`、`PriorityQueue`）的原理细节见 [11-java-collections.md](11-java-collections.md)。
- 项目案例里 ZSET、`PEXPIRE`、Lua 脚本的更多背景见 [50-redis.md](50-redis.md)。
