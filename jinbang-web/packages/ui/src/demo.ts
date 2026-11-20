export interface DemoAccount {
  label: string
  account: string
  password: string
}

/** 后端 dev 配置自带的演示账号（见 jinbang-platform/README.md）。只在本地开发环境的登录页展示。 */
export const DEMO_ACCOUNTS: DemoAccount[] = [
  { label: '学校管理员', account: '13900000001', password: 'Demo1234' },
  { label: '班主任', account: '13900000002', password: 'Demo1234' },
  { label: '数学老师', account: '13900000003', password: 'Demo1234' },
  { label: '年级主任', account: '13900000004', password: 'Demo1234' },
  { label: '游客教师', account: '13900000009', password: 'Demo1234' },
]
