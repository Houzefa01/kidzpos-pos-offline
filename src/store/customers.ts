import { create } from "zustand";
import { persist } from "zustand/middleware";
import { pushMutation } from "@/store/backend";
import { newId } from "@/lib/ids";

export interface Customer {
  id: string;
  name?: string;
  phone?: string;
  email?: string;
  points: number;
  totalSpent: number;
  visits: number;
  createdAt: string;
}

interface CustomersState {
  customers: Customer[];
  addCustomer: (c: Omit<Customer, "id" | "points" | "totalSpent" | "visits" | "createdAt"> & Partial<Pick<Customer, "points">>) => Customer;
  updateCustomer: (id: string, patch: Partial<Customer>) => void;
  deleteCustomer: (id: string) => void;
  applyPurchase: (id: string, totalPaid: number, pointsEarned: number, pointsRedeemed: number) => void;
}

export const useCustomers = create<CustomersState>()(
  persist(
    (set) => ({
      customers: [],
      addCustomer: (c) => {
        const full: Customer = {
          id: newId("c-"),
          name: c.name?.trim() || undefined,
          phone: c.phone?.trim() || undefined,
          email: c.email?.trim() || undefined,
          points: c.points ?? 0,
          totalSpent: 0,
          visits: 0,
          createdAt: new Date().toISOString(),
        };
        set((s) => ({ customers: [full, ...s.customers] }));
        void pushMutation("/api/customers", "POST", full, `customer:${full.id}`);
        return full;
      },
      updateCustomer: (id, patch) => {
        set((s) => ({ customers: s.customers.map((c) => (c.id === id ? { ...c, ...patch } : c)) }));
        void pushMutation(`/api/customers/${id}`, "PUT", patch, `customer:${id}`);
      },
      deleteCustomer: (id) => {
        set((s) => ({ customers: s.customers.filter((c) => c.id !== id) }));
        void pushMutation(`/api/customers/${id}`, "DELETE", undefined, `customer:${id}`);
      },
      applyPurchase: (id, totalPaid, pointsEarned, pointsRedeemed) =>
        set((s) => ({
          customers: s.customers.map((c) =>
            c.id === id
              ? {
                  ...c,
                  points: Math.max(0, c.points + pointsEarned - pointsRedeemed),
                  totalSpent: +(c.totalSpent + totalPaid).toFixed(2),
                  visits: c.visits + 1,
                }
              : c
          ),
        })),
    }),
    { name: "kidzpos-customers" }
  )
);
