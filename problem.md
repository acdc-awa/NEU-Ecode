为什么会有这些边框？
在 res/layout/activity_settings.xml 文件中，可以看到以下原因：

MaterialCardView 的默认行为： 你为设置项使用了 com.google.android.material.card.MaterialCardView 作为容器。在 Material 3 主题下，当你设置 app:cardElevation="0dp"（即取消阴影）时，Material 组件库为了让卡片与背景有所区分，默认会应用一个 1dp 的描边 (Stroke)。

涉及位置：账号区容器、导航区容器、关于区容器。
按钮样式设置： “切换账号”按钮明确指定了 style="@style/Widget.Material3.Button.OutlinedButton"。

OutlinedButton 在 Material 设计中定义就是带边框但无填充色的按钮。
如何去掉这些边框？
如果你觉得这些边框不好看，想让界面更简洁，可以按照以下方法修改 res/layout/activity_settings.xml：

1. 去掉卡片容器的边框
在所有的 MaterialCardView 标签中添加 app:strokeWidth="0dp"，或者直接去掉背景色，例如：

xml
<com.google.android.material.card.MaterialCardView
    ...
    app:cardElevation="0dp"
    app:strokeWidth="0dp"> <!-- 添加这一行来去掉边框 -->
2. 修改按钮样式
如果你不想要“切换账号”按钮的边框，可以将它的 style 改为实心按钮或文字按钮：

实心按钮（蓝色背景）：style="@style/Widget.Material3.Button.FilledButton"
文字按钮（无边框无背景）：style="@style/Widget.Material3.Button.TextButton"
3. 推荐的视觉优化建议
目前的界面中出现了“卡片套按钮”的双重边框（如下图“切换账号”处），看起来比较凌乱。通常建议：

要么去掉外部 MaterialCardView 的边框和背景，让按钮直接浮在页面背景上。
要么将按钮改为 FilledButton，与卡片的细边框形成对比。