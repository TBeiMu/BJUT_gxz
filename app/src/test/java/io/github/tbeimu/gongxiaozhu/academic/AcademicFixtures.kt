package io.github.tbeimu.gongxiaozhu.academic

import java.security.KeyPairGenerator
import java.util.Base64

internal val academicTestKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
internal val academicTestPublicKey: String get() = "-----BEGIN PUBLIC KEY-----" +
    Base64.getEncoder().encodeToString(academicTestKey.public.encoded) + "-----END PUBLIC KEY-----"

internal fun academicLoginFixture(state: String = "fictional-state") = """<html><head><meta charset="utf-8"></head><body>
    <form id="Form1" action="./login.aspx" method="post">
    <input type="hidden" name="__VIEWSTATE" value="$state"><input type="hidden" name="__VIEWSTATEGENERATOR" value="fake-generator">
    <input id="ctl00_pubkey" name="ctl00${'$'}pubkey" type="hidden" value="$academicTestPublicKey">
    <input id="ctl00_txtusername" name="ctl00${'$'}txtusername"><input id="ctl00_txtpassword" name="ctl00${'$'}txtpassword" type="password">
    <input id="ctl00_txtyzm" name="ctl00${'$'}txtyzm"><img id="ctl00_myCode" src="data:image/png;base64,AA==">
    <input type="image" id="ctl00_ImageButton1" name="ctl00${'$'}ImageButton1"></form></body></html>"""

internal fun academicGradeFixture(degreeRows: String = gradeRow(), degreePager: String = "", electiveRows: String = gradeRow("选修示例", "未公布"), student: String = "S209900001") = """
    <html><head><meta charset="utf-8"></head><body><form method="post" action="./xskccjcx.aspx">
    <input type="hidden" name="__VIEWSTATE" value="fictional-grades-state">
    <table><tr><td>学号</td><td>$student</td><td>姓名</td><td>虚构同学</td></tr></table>
    <h3>学位课程</h3><table id="MainWork_dgData"><tr><td>课程</td><td>课程学分</td><td>选修学期</td><td>成绩</td></tr>$degreeRows$degreePager</table>
    <h3>选修课程</h3><table id="MainWork_Datagrid1"><tr><td>课程</td><td>课程学分</td><td>选修学期</td><td>成绩</td></tr>$electiveRows</table>
    <table><tr><td>学位学分</td><td>3</td><td>选修学分</td><td>未获取</td></tr>
    <tr><td>已修学分</td><td>3</td><td>应修学分</td><td>30</td></tr></table></form></body></html>"""

internal fun gradeRow(course: String = "示例课程", grade: String = "91") =
    "<tr><td>$course</td><td>3</td><td>2099-2100秋学期</td><td>$grade</td></tr>"

internal fun academicExamFixture(rows: String = examRow(), pager: String = "", state: String = "fictional-exam-state") = """
    <html><head><meta charset="utf-8"></head><body><form method="post" action="./pykscx.aspx">
    <input type="hidden" name="__VIEWSTATE" value="$state"><input type="hidden" name="__EVENTTARGET" value="">
    <select id="MainWork_txtxq" name="ctl00${'$'}MainWork${'$'}txtxq"><option selected value="">--全部--</option><option value="20991">2099-2100秋学期</option></select>
    <input type="image" id="MainWork_btnSearch" name="ctl00${'$'}MainWork${'$'}btnSearch">
    <table id="MainWork_dgData"><tr><td>课程编号</td><td>课程名称</td><td>班级名称</td><td>开始时间</td><td>结束时间</td><td>考试地点</td></tr>$rows$pager</table>
    </form></body></html>"""

internal fun examRow(name: String = "虚构考试", location: String = "示例楼101") =
    "<tr><td>TEST-001</td><td>$name</td><td>虚构班</td><td>2099-12-01 09:00:00</td><td>2099-12-01 11:00:00</td><td>$location</td></tr>"

internal fun academicPager(columns: Int, grid: String = "dgData", page: Int = 1, next: Int = 2) =
    "<tr><td colspan='$columns'><span>$page</span> <a href=\"javascript:__doPostBack('ctl00${'$'}MainWork${'$'}$grid','Page${'$'}$next')\">$next</a></td></tr>"
