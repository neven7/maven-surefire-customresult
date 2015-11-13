maven-surefire-customresult

背景：自定义输出mvn surefire结果， 默认的结果只有Failed 和 Error的失败信息，并且打印的结果格式多样，不方便结果统计和正则匹配失败用例。
默认surefire插件输出结果几种常见的格式：


  AccountUpdatePrivacyCommentTest.setUpBeforeClass:69 1

  AccountUpdatePrivacyCommentTest.testAttentionByComment:136
Expected: is "省略"
     but: was "省略"

com.weibo.cases.maincase.XuelianCommentsHotTimelineWithFilterHotTimelineCommentBVTTest.testFilterCreate(com.weibo.cases.maincase.XuelianCommentsHotTimelineWithFilterHotTimelineCommentBVTTest)
  Run 1: XuelianCommentsHotTimelineWithFilterHotTimelineCommentBVTTest.setUp:79 NullPointer
  Run 2: XuelianCommentsHotTimelineWithFilterHotTimelineCommentBVTTest.tearDown:110
Expected: is "true"
     but: was null     

StatusShoppingTitleStatusTest.testFriendsTimelineRecom:245->createPage:319 This is error,should create page success!
如果断言是用hamcrest，输出结果为多行，因为hamcrest源码使用换行符"\n""打印实际和预期：

https://github.com/hamcrest/JavaHamcrest

path: JavaHamcrest/hamcrest-core/src/main/java/org/hamcrest/MatcherAssert.java

public static <T> void assertThat(String reason, T actual, Matcher<? super T> matcher) {
        if (!matcher.matches(actual)) {
            Description description = new StringDescription();
            description.appendText(reason)
                       .appendText("\nExpected: ")
                       .appendDescriptionOf(matcher)
                       .appendText("\n     but: ");
            matcher.describeMismatch(actual, description);

            throw new AssertionError(description.toString());
        }
    }
实现自定义输出格式：
为了便于mvn test迭代重试，故要统一输出格式，否则需正则匹配各种不样的格式，开销大且易漏掉失败用例，修改了maven surefire源码，统一输出结果并将成功和skipped的用例信息也打印出来，方便统计结果信息

当你执行mvn test -D=TestClass时，终端结果输出格式统一输出如下（并且每条用例信息都为1行，方便正则匹配）：
CustomResult Failed StackTrace@错误栈信息

CustomResult Fail@失败信息

CustomResult Error@失败信息

CustomResult Skipped@skipped信息

CustomResult Success@成功信息

结果比照：
默认surefire Result：
![这里写图片描述](http://img.blog.csdn.net/20151113092650360)

maven-surefire-customresult Result:
![这里写图片描述](http://img.blog.csdn.net/20151113092708318)

使用方法：
https://github.com/neven7/maven-surefire-customresult 选择分支 Branch: extensions-2.19

下载源码

版本已经修改为2.19.1

1.本地使用，进入项目根目录，执行mvn clean -Dcheckstyle.skip=true -Dmaven.test.skip=true -DuniqueVersion=false -Denforcer.skip=true install 本地安装， 在测试项目pom.xml中引用如下：

<plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>2.19.1</version>
                <configuration>
                    <forkMode>pertest</forkMode>
                    <argLine>-Xms1024m -Xmx1024m</argLine>
                    <printSummary>true</printSummary>
                    <testFailureIgnore>true</testFailureIgnore>
                </configuration>
                <dependencies>
                    <dependency>
                        <groupId>org.apache.maven.surefire</groupId>
                        <artifactId>surefire-junit47</artifactId>
                        <version>2.19</version>
                    </dependency>
                </dependencies>
            </plugin>
        </plugins>
2.公司内部使用，进入项目根目录，执行mvn clean -Dcheckstyle.skip=true -Dmaven.test.skip=true -DuniqueVersion=false -Denforcer.skip=true deploy 需要在项目pom.xml配置自己公司的私有仓库：

<distributionManagement>
        <repository>
            <id>weiboqa</id>
            <name>weiboqacontent</name>
            <url>http://ip:port/nexus/content/repositories/weiboqa</url>
        </repository>
 </distributionManagement>
在settings.xml配置私有仓库的账号和密码才能发布到私有仓库，发布成功后，测试项目pom.xml引用如上。
